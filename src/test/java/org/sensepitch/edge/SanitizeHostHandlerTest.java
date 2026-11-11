package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.SanitizeHostHandler.MISSING_HOST;
import static org.sensepitch.edge.SanitizeHostHandler.UNKNOWN_HOST;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class SanitizeHostHandlerTest {

  final SanitizeHostHandler handler =
      new SanitizeHostHandler(List.of("example.com", "[2001:db8::1]", "192.0.2.1"));

  HttpRequest pass(HttpRequest request) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    channel.writeInbound(request);
    HttpRequest result = channel.readInbound();
    channel.finishAndReleaseAll();
    return result;
  }

  HttpRequest request(HttpMethod method, String... hosts) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    for (String host : hosts) {
      request.headers().add(HttpHeaderNames.HOST, host);
    }
    return request;
  }

  String sanitize(String... hosts) {
    HttpRequest result = pass(request(HttpMethod.GET, hosts));
    assertThat(result.headers().getAll(HttpHeaderNames.HOST)).hasSize(1);
    return result.headers().get(HttpHeaderNames.HOST);
  }

  @Test
  void knownHost() {
    assertThat(sanitize("example.com")).isEqualTo("example.com");
    assertThat(sanitize("192.0.2.1")).isEqualTo("192.0.2.1");
    assertThat(sanitize("[2001:db8::1]")).isEqualTo("[2001:db8::1]");
  }

  @Test
  void unknownOrMissingHost() {
    assertThat(sanitize("other.com")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize()).isEqualTo(MISSING_HOST);
  }

  @Test
  void portIsRemoved() {
    assertThat(sanitize("example.com:8443")).isEqualTo("example.com");
    assertThat(sanitize("192.0.2.1:443")).isEqualTo("192.0.2.1");
    assertThat(sanitize("[2001:db8::1]:8443")).isEqualTo("[2001:db8::1]");
    // empty port is valid according to RFC 3986
    assertThat(sanitize("example.com:")).isEqualTo("example.com");
  }

  @Test
  void malformedPortOrAddressIsUnknown() {
    assertThat(sanitize("example.com:abc")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:443:1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:1234567")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("2001:db8::1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("[2001:db8::1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("[2001:db8::1]x")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize(":443")).isEqualTo(UNKNOWN_HOST);
  }

  /// Host names are case-insensitive, a trailing dot denotes the same, fully qualified name.
  @Test
  void caseAndTrailingDotAreNormalized() {
    assertThat(sanitize("Example.COM")).isEqualTo("example.com");
    assertThat(sanitize("example.com.")).isEqualTo("example.com");
    assertThat(sanitize("EXAMPLE.com.:443")).isEqualTo("example.com");
    assertThat(sanitize("[2001:DB8::1]")).isEqualTo("[2001:db8::1]");
    assertThat(sanitize("example.com..")).isEqualTo(UNKNOWN_HOST);
  }

  /// RFC 9112 section 3.2: a request with more than one Host header must be rejected. The unknown
  /// host is rejected with 400 by [UnservicedHost]. Removing the extra headers makes sure an
  /// upstream never sees a different host than the one the proxy decided on.
  @Test
  void multipleHostHeadersAreUnknown() {
    assertThat(sanitize("example.com", "example.com")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com", "evil.com")).isEqualTo(UNKNOWN_HOST);
  }

  @Test
  void unsupportedMethodIsReplaced() {
    for (String method : List.of("TRACE", "CONNECT", "FOO")) {
      HttpRequest result = pass(request(HttpMethod.valueOf(method), "example.com"));
      assertThat(result.method()).isEqualTo(SanitizeHostHandler.UNKNOWN_METHOD);
    }
    for (String method : SanitizeHostHandler.SUPPORTED_HTTP_METHODS) {
      HttpRequest result = pass(request(HttpMethod.valueOf(method), "example.com"));
      assertThat(result.method().name()).isEqualTo(method);
    }
  }

  @Test
  void configuredHostMustBeNormalized() {
    for (String host : List.of("Example.com", "example.com.", "example.com:443", "a b")) {
      assertThatThrownBy(() -> SanitizeHostHandler.filterKnownHosts(List.of(host)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(host);
    }
  }
}
