package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Response header sanitization in [DownstreamHandler], without opening upstream sockets.
@ExtendWith(SerenityJUnit5Extension.class)
class DownstreamHeadersBDDTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"Connection", "Keep-Alive", "Proxy-Connection", "TE", "Trailer", "Upgrade"})
  void connectionHeadersAreRemoved(String name) {
    new Steps()
        .given_response_header(name, "upstream-only")
        .when_response_is_forwarded()
        .then_header_is_absent(name);
  }

  @Test
  void allConnectionFieldsAreReadBeforeRemovingNamedHeaders() {
    new Steps()
        .given_response_header("Connection", "Connection, X-Private, ,\tX-Other\t,")
        .given_response_header("cOnNeCtIoN", "x-third")
        .given_response_header("x-private", "first")
        .given_response_header("X-Private", "second")
        .given_response_header("X-Other", "other")
        .given_response_header("X-Third", "third")
        .when_response_is_forwarded()
        .then_header_is_absent("Connection")
        .then_header_is_absent("X-Private")
        .then_header_is_absent("X-Other")
        .then_header_is_absent("X-Third");
  }

  @Test
  void ordinaryResponseHeadersArePreserved() {
    new Steps()
        .given_response_header("Connection", "close")
        .given_response_header("Content-Type", "text/plain")
        .given_response_header("Content-Length", "3")
        .given_response_header("Set-Cookie", "first=1")
        .given_response_header("Set-Cookie", "second=2")
        .when_response_is_forwarded()
        .then_header_is_absent("Connection")
        .then_header_values_are("Content-Type", "text/plain")
        .then_header_values_are("Content-Length", "3")
        .then_header_values_are("Set-Cookie", "first=1", "second=2");
  }

  @Test
  void chunkedFramingIsPreserved() {
    new Steps()
        .given_response_header(HttpHeaderNames.TRANSFER_ENCODING.toString(), "chunked")
        .given_response_header("Trailer", "X-Checksum")
        .when_response_is_forwarded()
        .then_header_is_absent("Trailer")
        .then_header_values_are("Transfer-Encoding", "chunked");
  }

  public static class Steps {

    private final HttpResponse response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

    @Step
    public Steps given_response_header(String name, String value) {
      response.headers().add(name, value);
      return this;
    }

    @Step
    public Steps when_response_is_forwarded() {
      EmbeddedChannel channel =
          new EmbeddedChannel(new DownstreamHandler(null, new ProxyMetrics()));
      try {
        assertThat(channel.writeOutbound(response)).isTrue();
        assertThat((Object) channel.readOutbound()).isSameAs(response);
      } finally {
        channel.finishAndReleaseAll();
      }
      return this;
    }

    @Step
    public Steps then_header_is_absent(String name) {
      assertThat(response.headers().contains(name)).as(name).isFalse();
      return this;
    }

    @Step
    public Steps then_header_values_are(String name, String... values) {
      assertThat(response.headers().getAll(name)).as(name).containsExactly(values);
      return this;
    }
  }
}
