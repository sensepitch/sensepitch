package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.MockTicker;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.Ticker;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Response timeouts have one completion path, including when the client delays the error write
/// or the upstream is still connecting. The other timeout behaviours are in [TimeoutsTest].
@ExtendWith(SerenityJUnit5Extension.class)
class ResponseTimeoutBDDTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void timeoutCompletesOnce(boolean delayedWrite) {
    try (Steps steps = new Steps()) {
      steps
          .given_a_silent_upstream_and_client(delayedWrite)
          .when_the_response_timeout_expires()
          .then_the_upstream_is_closed()
          .then_the_client_waits_for_the_error_write(delayedWrite)
          .when_the_client_accepts_the_response()
          .then_one_gateway_timeout_is_logged_and_the_connection_is_closed();
      assertThat(logs.messages(ExceptionHandler.class)).isEmpty();
    }
  }

  @Test
  void timeoutWhileConnectingClosesTheLateUpstream() {
    try (Steps steps = new Steps()) {
      steps
          .given_an_upstream_still_connecting()
          .when_the_response_timeout_expires()
          .when_the_client_accepts_the_response()
          .then_one_gateway_timeout_is_logged_and_the_connection_is_closed()
          .when_the_upstream_connection_is_established()
          .then_the_upstream_is_closed();
      assertThat(logs.messages(ExceptionHandler.class)).isEmpty();
    }
  }

  static class Steps implements AutoCloseable {
    private final MockTicker ticker = Ticker.newMockTicker();
    private final ProxyMetrics metrics = new ProxyMetrics();
    private final RequestLogCapture requestLog = new RequestLogCapture();
    private final EmbeddedChannel upstream = new EmbeddedChannel();
    private final FlowAndExceptionTest.FlushBlocker writes =
        new FlowAndExceptionTest.FlushBlocker();
    private EmbeddedChannel ingress;
    private Promise<Channel> connecting;

    @Step
    Steps given_a_silent_upstream_and_client(boolean delayedWrite) {
      writes.blocked = delayedWrite;
      startRequest(ctx -> ctx.executor().newSucceededFuture(upstream));
      assertThat(upstream.isActive()).isTrue();
      return this;
    }

    @Step
    Steps given_an_upstream_still_connecting() {
      startRequest(ctx -> connecting = ctx.executor().newPromise());
      return this;
    }

    /// Sends a request without content through the ingress pipeline, shared by the givens
    private void startRequest(Upstream upstreamConnector) {
      ingress =
          EmbeddedChannel.builder()
              .ticker(ticker)
              .handlers(
                  writes,
                  new TimeoutsTest.FakeSslHandler(),
                  new RequestLoggingHandler(metrics, requestLog.plainText()),
                  new IngressTimeoutHandler(
                      ConnectionConfig.DEFAULT.toBuilder().responseTimeoutSeconds(30).build(),
                      metrics),
                  new HttpServerKeepAliveHandler(),
                  new DownstreamHandler(upstreamConnector, metrics),
                  new ExceptionHandler(metrics))
              .build();
      ingress.runPendingTasks();
      ingress.writeInbound(
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/silent"),
          LastHttpContent.EMPTY_LAST_CONTENT);
      ingress.runPendingTasks();
    }

    @Step
    Steps when_the_response_timeout_expires() {
      ticker.advance(30, SECONDS);
      ingress.runScheduledPendingTasks();
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_connection_is_established() {
      connecting.setSuccess(upstream);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_the_upstream_is_closed() {
      assertThat(upstream.isActive()).isFalse();
      return this;
    }

    @Step
    Steps then_the_client_waits_for_the_error_write(boolean delayedWrite) {
      assertThat(ingress.isActive()).isEqualTo(delayedWrite);
      if (delayedWrite) {
        assertThat(requestLog.lines()).isEmpty();
      }
      return this;
    }

    @Step
    Steps when_the_client_accepts_the_response() {
      writes.release(ingress);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_one_gateway_timeout_is_logged_and_the_connection_is_closed() {
      FullHttpResponse response = ingress.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
            .isEqualTo(HttpHeaderValues.CLOSE.toString());
        assertThat((Object) ingress.readOutbound()).isNull();
        assertThat(ingress.isActive()).isFalse();
        assertThat(requestLog.lines()).singleElement().asString().contains("/silent HTTP/1.1 504");
        assertThat(requestLog.outcomes()).containsExactly(RequestOutcome.UPSTREAM_TIMEOUT);
      } finally {
        response.release();
      }
      return this;
    }

    @Override
    public void close() {
      if (ingress != null) {
        ingress.finishAndReleaseAll();
      }
      upstream.finishAndReleaseAll();
    }
  }
}
