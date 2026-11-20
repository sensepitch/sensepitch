package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// An upstream informational response is forwarded, but does not end the exchange in
/// [ForwardHandler] or [DownstreamHandler]. The client receives it before the final response, and
/// the upstream stays open until that final response is complete.
@ExtendWith(SerenityJUnit5Extension.class)
class InformationalResponseBDDTest {

  private static final byte[] BODY = {1, 2, 3};

  @Test
  void continuePrecedesTheFinalResponseAndKeepsTheUpstreamOpen() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_post_expecting_continue()
          .when_the_upstream_sends_continue()
          .then_the_client_receives_continue_in_order()
          .then_the_upstream_is_open()
          .then_only_these_responses_are_logged()
          .when_the_client_sends_the_body()
          .then_the_upstream_receives_the_body()
          .when_the_upstream_sends_the_final_response()
          .then_the_client_receives_the_final_response_in_order()
          .then_only_these_responses_are_logged(HttpResponseStatus.OK)
          .then_the_upstream_is_closed();
    }
  }

  @Test
  void singleFinalResponseStillEndsTheExchange() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_get_request()
          .when_the_upstream_sends_the_final_response()
          .then_the_client_receives_the_final_response_in_order()
          .then_only_these_responses_are_logged(HttpResponseStatus.OK)
          .then_the_upstream_is_closed();
    }
  }

  static class Steps implements AutoCloseable {

    private final ProxyMetrics metrics = new ProxyMetrics();
    private final List<HttpResponseStatus> loggedStatuses = new ArrayList<>();
    private final EmbeddedChannel upstream = new EmbeddedChannel();
    private final EmbeddedChannel ingress;
    private HttpRequest request;
    private HttpContent body;

    Steps() {
      ingress =
          new EmbeddedChannel(
              new RequestLoggingHandler(
                  metrics, log -> loggedStatuses.add(log.response().status())),
              new IngressTimeoutHandler(ConnectionConfig.DEFAULT, metrics),
              new DownstreamHandler(ctx -> ctx.executor().newSucceededFuture(upstream), metrics),
              new ExceptionHandler(metrics));
      upstream.pipeline().addLast(new ForwardHandler(ingress, metrics, "upstream"));
    }

    @Step
    Steps given_a_post_expecting_continue() {
      request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
      HttpUtil.setContentLength(request, BODY.length);
      request.headers().set(HttpHeaderNames.EXPECT, "100-continue");
      ingress.writeInbound(request);
      rattle();
      upstream.flush();
      assertThat((Object) upstream.readOutbound()).isSameAs(request);
      return this;
    }

    @Step
    Steps given_a_get_request() {
      request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
      ingress.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
      rattle();
      assertThat((Object) upstream.readOutbound()).isSameAs(request);
      assertThat((Object) upstream.readOutbound()).isSameAs(LastHttpContent.EMPTY_LAST_CONTENT);
      return this;
    }

    @Step
    Steps when_the_upstream_sends_continue() {
      HttpResponse response =
          new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
      response.headers().set(HttpHeaderNames.CONNECTION, "X-Private, Upgrade");
      response.headers().set("X-Private", "upstream-only");
      response.headers().set(HttpHeaderNames.UPGRADE, "h2c");
      upstream.writeInbound(response, LastHttpContent.EMPTY_LAST_CONTENT);
      rattle();
      return this;
    }

    @Step
    Steps then_the_client_receives_continue_in_order() {
      HttpResponse response = FlowAndExceptionTest.readSkippingEmptyBuffers(ingress);
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.CONTINUE);
        assertThat(response.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
        assertThat(response.headers().contains("X-Private")).isFalse();
        assertThat(response.headers().contains(HttpHeaderNames.UPGRADE)).isFalse();
        assertThat((Object) ingress.readOutbound()).isInstanceOf(LastHttpContent.class);
        assertThat((Object) ingress.readOutbound()).isNull();
      } finally {
        ReferenceCountUtil.release(response);
      }
      return this;
    }

    @Step
    Steps then_the_upstream_is_open() {
      assertThat(upstream.isOpen()).isTrue();
      return this;
    }

    @Step
    Steps when_the_client_sends_the_body() {
      body = new DefaultHttpContent(Unpooled.wrappedBuffer(BODY));
      ingress.writeInbound(body, LastHttpContent.EMPTY_LAST_CONTENT);
      rattle();
      return this;
    }

    @Step
    Steps then_the_upstream_receives_the_body() {
      HttpContent received = upstream.readOutbound();
      assertThat((Object) received).isSameAs(body);
      assertThat((Object) upstream.readOutbound()).isSameAs(LastHttpContent.EMPTY_LAST_CONTENT);
      ReferenceCountUtil.release(received);
      return this;
    }

    @Step
    Steps when_the_upstream_sends_the_final_response() {
      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      HttpUtil.setContentLength(response, BODY.length);
      response.headers().set(HttpHeaderNames.CONNECTION, "X-Final-Private");
      response.headers().set("X-Final-Private", "upstream-only");
      response.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=5");
      upstream.writeInbound(
          response,
          new DefaultHttpContent(Unpooled.wrappedBuffer(BODY)),
          new DefaultLastHttpContent());
      rattle();
      return this;
    }

    @Step
    Steps then_the_client_receives_the_final_response_in_order() {
      HttpResponse response = FlowAndExceptionTest.readSkippingEmptyBuffers(ingress);
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().contains("X-Final-Private")).isFalse();
        assertThat(response.headers().get(HttpHeaderNames.KEEP_ALIVE)).doesNotContain("timeout=5");
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
        HttpContent content = FlowAndExceptionTest.readSkippingEmptyBuffers(ingress);
        try {
          assertThat(content.content().readableBytes()).isEqualTo(BODY.length);
        } finally {
          content.release();
        }
        assertThat((Object) ingress.readOutbound()).isInstanceOf(LastHttpContent.class);
        assertThat((Object) ingress.readOutbound()).isNull();
      } finally {
        ReferenceCountUtil.release(response);
      }
      return this;
    }

    @Step
    Steps then_the_upstream_is_closed() {
      assertThat(upstream.isOpen()).isFalse();
      return this;
    }

    @Step
    Steps then_only_these_responses_are_logged(HttpResponseStatus... statuses) {
      assertThat(loggedStatuses).containsExactly(statuses);
      assertThat(
              metrics.ingressRequestsCompleted.labelValues(SanitizeHostHandler.MISSING_HOST).get())
          .isEqualTo(statuses.length);
      return this;
    }

    /// Runs the deferred writes between the two embedded event loops.
    private void rattle() {
      while (ingress.hasPendingTasks() || upstream.hasPendingTasks()) {
        ingress.runPendingTasks();
        upstream.runPendingTasks();
      }
    }

    @Override
    public void close() {
      ingress.finishAndReleaseAll();
      upstream.finishAndReleaseAll();
    }
  }
}
