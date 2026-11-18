package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/// An upstream failure ends the exchange in [DownstreamHandler#completeWithError]: before the
/// response has started the client gets a 502, afterwards the connections are dropped without a
/// second response. The upstream reports a failed request write asynchronously, so it can arrive
/// at any point of the response, or after it. More upstream error cases are in
/// [FlowAndExceptionTest].
@ExtendWith(SerenityJUnit5Extension.class)
class UpstreamFailureBDDTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  void writeFailureBeforeTheResponseIsAnswered() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_a_bad_gateway("Upstream write problem")
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_reported_at_debug(RequestOutcome.UPSTREAM_WRITE_FAILED);
    }
  }

  @Test
  void connectFailureIsAnswered() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_while_the_upstream_is_connecting()
          .when_the_upstream_connect_fails()
          .then_the_client_received_only_a_bad_gateway("Upstream connection problem")
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CONNECT_FAILED)
          .then_the_failure_is_reported_at_debug(RequestOutcome.UPSTREAM_CONNECT_FAILED);
    }
  }

  @Test
  void writeFailureAfterTheHeadersDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_the_upstream_response(0)
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_reported_at_debug(RequestOutcome.UPSTREAM_WRITE_FAILED);
    }
  }

  @Test
  void writeFailureAfterAPartialBodyDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_the_upstream_response(2)
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_reported_at_debug(RequestOutcome.UPSTREAM_WRITE_FAILED);
    }
  }

  /// The upstream may answer before it read the whole request, and the failed request write
  /// arrives after the response; the next request on the connection must not be affected.
  @Test
  void writeFailureAfterTheResponseIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_completes_the_response()
          .when_the_upstream_write_fails()
          .then_the_client_received_the_complete_response()
          .then_the_client_connection_is_open()
          .then_the_upstream_is_closed()
          .then_the_outcome_is(RequestOutcome.COMPLETED)
          .then_the_late_failure_is_ignored();
    }
  }

  @Test
  void secondRequestAfterTheHeadersDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_client_sends_another_request()
          .then_the_client_received_only_the_upstream_response(1)
          .then_the_connections_are_closed();
    }
  }

  /// The refusal of the second request ends the exchange, the connect failure that follows is
  /// not answered a second time.
  @Test
  void connectFailureAfterTheExchangeEndedIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_while_the_upstream_is_connecting()
          .when_the_client_sends_another_request()
          .when_the_upstream_connect_fails()
          .then_the_client_received_only_a_bad_request()
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.COMPLETED)
          .then_the_late_failure_is_ignored();
    }
  }

  class Steps implements AutoCloseable {
    private final ProxyMetrics metrics = new ProxyMetrics();
    private final RequestLogCapture requestLog = new RequestLogCapture();
    private final List<Throwable> loggedErrors = new ArrayList<>();
    private Throwable failure;
    private final HoldLastContentWrite heldWrite = new HoldLastContentWrite();
    private EmbeddedChannel ingress;
    private EmbeddedChannel upstream;
    private Promise<Channel> connecting;

    @Step
    Steps given_a_request_forwarded_upstream() {
      startRequest(
          ctx -> {
            upstream = new EmbeddedChannel(heldWrite, new ForwardHandler(ctx.channel()));
            return ctx.executor().newSucceededFuture(upstream);
          });
      assertThat(heldWrite.promise).as("request is written upstream").isNotNull();
      return this;
    }

    @Step
    Steps given_a_request_while_the_upstream_is_connecting() {
      startRequest(ctx -> connecting = ctx.executor().newPromise());
      return this;
    }

    /// Sends a request without content through the ingress pipeline, shared by the givens
    private void startRequest(Upstream upstreamConnector) {
      RequestLogger logger = requestLog.plainText();
      ingress =
          new EmbeddedChannel(
              new TimeoutsTest.FakeSslHandler(),
              new RequestLoggingHandler(
                  metrics,
                  info -> {
                    loggedErrors.add(info.error());
                    logger.logRequest(info);
                  }),
              new HttpServerKeepAliveHandler(),
              new DownstreamHandler(upstreamConnector, metrics),
              new ExceptionHandler(metrics));
      ingress.runPendingTasks();
      ingress.writeInbound(
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data"),
          LastHttpContent.EMPTY_LAST_CONTENT);
      ingress.runPendingTasks();
    }

    @Step
    Steps when_the_upstream_sends_the_response_headers() {
      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      return fromUpstream(response);
    }

    @Step
    Steps when_the_upstream_sends_a_body_part() {
      return fromUpstream(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1, 2, 3})));
    }

    @Step
    Steps when_the_upstream_completes_the_response() {
      return fromUpstream(new DefaultLastHttpContent());
    }

    /// Passes a response part from the upstream to the client, which receives it at once
    private Steps fromUpstream(Object msg) {
      upstream.writeInbound(msg);
      ingress.flush();
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_write_fails() {
      failure = new IOException("Broken pipe");
      heldWrite.promise.setFailure(failure);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_connect_fails() {
      failure = new ConnectException("Connection refused");
      connecting.setFailure(failure);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_client_sends_another_request() {
      ingress.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/2"));
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_the_client_received_only_a_bad_gateway(String reason) {
      HttpResponse response = onlyResponse();
      assertThat(response.status().code()).isEqualTo(502);
      assertThat(response.status().reasonPhrase()).isEqualTo(reason);
      return this;
    }

    @Step
    Steps then_the_client_received_only_a_bad_request() {
      assertThat(onlyResponse().status().code()).isEqualTo(400);
      return this;
    }

    /// The single message the client received, a complete error response
    private HttpResponse onlyResponse() {
      List<Object> received = received();
      assertThat(received).singleElement().isInstanceOf(LastHttpContent.class);
      return (HttpResponse) received.get(0);
    }

    @Step
    Steps then_the_client_received_only_the_upstream_response(int bodyParts) {
      List<Object> received = received();
      assertThat(received).hasSize(1 + bodyParts);
      HttpResponse response = (HttpResponse) received.get(0);
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(received.subList(1, received.size()))
          .allSatisfy(
              part ->
                  assertThat(part)
                      .isInstanceOf(HttpContent.class)
                      .isNotInstanceOf(LastHttpContent.class));
      return this;
    }

    @Step
    Steps then_the_client_received_the_complete_response() {
      List<Object> received = received();
      assertThat(received).hasSize(3);
      assertThat(((HttpResponse) received.get(0)).status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(received.get(2)).isInstanceOf(LastHttpContent.class);
      return this;
    }

    /// All messages written to the client so far
    private List<Object> received() {
      List<Object> received = new ArrayList<>();
      Object msg;
      while ((msg = ingress.readOutbound()) != null) {
        received.add(msg);
        ReferenceCountUtil.release(msg);
      }
      return received;
    }

    @Step
    Steps then_the_connections_are_closed() {
      then_the_client_connection_is_closed();
      return then_the_upstream_is_closed();
    }

    @Step
    Steps then_the_client_connection_is_closed() {
      assertThat(ingress.isOpen()).isFalse();
      return this;
    }

    @Step
    Steps then_the_client_connection_is_open() {
      assertThat(ingress.isOpen()).isTrue();
      return this;
    }

    @Step
    Steps then_the_upstream_is_closed() {
      assertThat(upstream.isOpen()).isFalse();
      return this;
    }

    /// @param outcome the outcome of the single logged request
    @Step
    Steps then_the_outcome_is(RequestOutcome outcome) {
      assertThat(requestLog.outcomes()).containsExactly(outcome);
      assertThat(metrics.requestOutcomeCounter.collect().getDataPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getLabels().get("outcome")).isEqualTo(outcome.value());
                assertThat(point.getValue()).isEqualTo(1);
              });
      return this;
    }

    @Step
    Steps then_the_failure_is_reported_at_debug(RequestOutcome outcome) {
      assertThat(loggedErrors).containsExactly(failure);
      assertThat(logs.entries())
          .filteredOn(entry -> entry.source().equals(DownstreamHandler.class.getName()))
          .singleElement()
          .satisfies(
              entry -> {
                assertThat(entry.info().level()).isEqualTo(Level.DEBUG);
                assertThat(entry.message())
                    .contains(
                        "upstream failure",
                        "outcome=" + outcome,
                        failure.getClass().getName(),
                        failure.getMessage());
              });
      return this;
    }

    @Step
    Steps then_the_late_failure_is_ignored() {
      assertThat(loggedErrors).containsExactly((Throwable) null);
      assertThat(logs.messages(DownstreamHandler.class))
          .noneMatch(message -> message.contains("upstream failure"));
      return this;
    }

    @Override
    public void close() {
      if (ingress != null) {
        ingress.finishAndReleaseAll();
      }
      if (upstream != null) {
        upstream.finishAndReleaseAll();
      }
    }
  }

  /// Holds the write of the last request content on the upstream channel, so the test decides
  /// when and how it completes
  static class HoldLastContentWrite extends ChannelOutboundHandlerAdapter {
    ChannelPromise promise;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      if (msg instanceof LastHttpContent) {
        ReferenceCountUtil.release(msg);
        this.promise = promise;
        return;
      }
      super.write(ctx, msg, promise);
    }
  }
}
