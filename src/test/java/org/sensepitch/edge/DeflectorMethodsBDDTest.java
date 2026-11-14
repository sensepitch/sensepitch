package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.ArrayList;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Method restrictions apply before challenge endpoints, but after admission and bypass checks.
@ExtendWith(SerenityJUnit5Extension.class)
class DeflectorMethodsBDDTest {

  private final Steps steps = new Steps();

  @AfterEach
  void releaseMessages() {
    steps.channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"})
  void unadmittedMethodsAreRejected(String method) {
    steps.given_deflector_protection();
    for (String uri :
        List.of(
            "/protected",
            Deflector.CHALLENGE_STEP_URL + "?hidden=1&vis=hidden&event=init-hidden",
            Deflector.CHALLENGE_RESOURCES_URL + "/script.js",
            Deflector.CHALLENGE_ANSWER_URL)) {
      steps
          .when_request_is_sent(method, uri, null, null)
          .then_method_is_rejected()
          .then_request_body_is_discarded();
    }
    steps
        .then_method_rejections_are_counted_and_logged(4)
        .when_request_is_sent("GET", "/protected", null, null)
        .then_challenge_is_sent();
  }

  @Test
  void unadmittedGetReceivesChallenge() {
    steps
        .given_deflector_protection()
        .when_request_is_sent("GET", "/protected", null, null)
        .then_challenge_is_sent()
        .then_request_body_is_discarded();
  }

  @Test
  void unadmittedGetTelemetryIsHandledLocally() {
    steps
        .given_deflector_protection()
        .when_request_is_sent(
            "GET",
            Deflector.CHALLENGE_STEP_URL + "?hidden=1&vis=hidden&event=init-hidden",
            null,
            null)
        .then_telemetry_is_accepted()
        .then_request_body_is_discarded();
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"})
  void admissionAndBypassesAllowOtherMethods(String method) {
    steps
        .given_deflector_protection()
        .when_request_is_sent(method, "/protected", steps.token, null)
        .then_request_passes()
        .when_request_is_sent(method, "/bypass", null, null)
        .then_request_passes()
        .when_request_is_sent(method, "/protected", null, "Twitterbot/1.0")
        .then_request_passes();
  }

  public static class Steps {

    private final ProxyMetrics metrics = new ProxyMetrics();
    private final List<String> outcomes = new ArrayList<>();
    private EmbeddedChannel channel;
    private HttpRequest request;
    private String token;

    @Step
    public Steps given_deflector_protection() {
      String secret = "method-test-secret";
      token =
          new DefaultAdmissionTokenGenerator(new byte[] {127, 0, 0, 1}, 'X', secret).newAdmission();
      DeflectorConfig config =
          DeflectorConfig.builder()
              .serverIpv4Address("127.0.0.1")
              .tokenGenerators(
                  List.of(
                      AdmissionTokenGeneratorConfig.builder().prefix("X").secret(secret).build()))
              .bypass(BypassConfig.builder().uris(List.of("/bypass")).build())
              .detectCrawler(DetectCrawlerConfig.builder().build())
              .build();
      channel =
          new EmbeddedChannel(
              new RequestLoggingHandler(metrics, info -> outcomes.add(info.protectionOutcome())),
              new ProtectionHandler(new DeflectorHandler(new Deflector(config), metrics)));
      return this;
    }

    @Step
    public Steps when_request_is_sent(
        String method, String uri, String admission, String userAgent) {
      request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri);
      request.headers().set(HttpHeaderNames.HOST, "example.com");
      if (admission != null) {
        request
            .headers()
            .set(HttpHeaderNames.COOKIE, Deflector.TOKEN_COOKIE_NAME + "=" + admission);
      }
      if (userAgent != null) {
        request.headers().set(HttpHeaderNames.USER_AGENT, userAgent);
      }
      channel.writeInbound(request);
      return this;
    }

    @Step
    public Steps then_method_is_rejected() {
      assertThat((Object) channel.readInbound()).isNull();
      FullHttpResponse response = channel.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(response.content().readableBytes()).isZero();
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH)).isZero();
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.headers().contains(HttpHeaderNames.SET_COOKIE)).isFalse();
        assertThat(request.headers().get(Deflector.TRAFFIC_TYPE_HEADER))
            .isEqualTo(Deflector.TRAFFIC_UNKNOWN);
        assertThat(channel.isActive()).isTrue();
      } finally {
        response.release();
      }
      return this;
    }

    @Step
    public Steps then_request_body_is_discarded() {
      var content = new DefaultHttpContent(Unpooled.buffer().writeByte(1));
      var last = new DefaultLastHttpContent(Unpooled.buffer().writeByte(2));
      assertThat(channel.writeInbound(content, last)).isFalse();
      assertThat(content.refCnt()).isZero();
      assertThat(last.refCnt()).isZero();
      return this;
    }

    @Step
    public Steps then_method_rejections_are_counted_and_logged(int count) {
      assertThat(
              metrics.deflectorRequestCounter.labelValues("example.com", "method_rejected").get())
          .isEqualTo(count);
      assertThat(outcomes).hasSize(count).containsOnly("method_rejected");
      return this;
    }

    @Step
    public Steps then_telemetry_is_accepted() {
      assertThat((Object) channel.readInbound()).isNull();
      FullHttpResponse response = channel.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        assertThat(response.content().readableBytes()).isZero();
        assertThat(response.headers().get(HttpHeaderNames.CACHE_CONTROL)).contains("no-store");
        assertThat(response.headers().contains(HttpHeaderNames.SET_COOKIE)).isFalse();
        assertThat(
                metrics.deflectorRequestCounter.labelValues("example.com", "method_rejected").get())
            .isZero();
      } finally {
        response.release();
      }
      return this;
    }

    @Step
    public Steps then_challenge_is_sent() {
      FullHttpResponse response = channel.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(response.content().readableBytes()).isPositive();
        assertThat(response.headers().contains(HttpHeaderNames.SET_COOKIE)).isTrue();
      } finally {
        response.release();
      }
      return this;
    }

    @Step
    public Steps then_request_passes() {
      assertThat((Object) channel.readInbound()).isSameAs(request);
      assertThat((Object) channel.readOutbound()).isNull();
      channel.writeInbound(new DefaultLastHttpContent(Unpooled.buffer()));
      var last = (DefaultLastHttpContent) channel.readInbound();
      last.release();
      return this;
    }
  }
}
