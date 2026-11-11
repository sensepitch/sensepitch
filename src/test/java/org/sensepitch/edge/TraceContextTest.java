package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Parsing and generating the W3C `traceparent` header
///
/// @author Jens Wilke
class TraceContextTest {

  static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void parseValid() {
    TraceContext context = TraceContext.parse(VALID);
    assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(context.spanId()).isEqualTo("00f067aa0ba902b7");
    assertThat(context.flags()).isEqualTo("01");
    assertThat(context.header()).isEqualTo(VALID);
  }

  /// A future version may append fields, the known part is used
  @Test
  void parseFutureVersion() {
    TraceContext context =
        TraceContext.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra");
    assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(context.header()).startsWith("00-");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "garbage",
        // version ff is forbidden
        "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        // version 00 must not have more fields
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
        // future version with garbage after the flags
        "cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01x",
        // uppercase hex is not allowed
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        // all zero trace id or span id
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
        // wrong separators and lengths
        "00_4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e473-600f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0g",
        "zz-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
      })
  void parseInvalid(String header) {
    assertThat(TraceContext.parse(header)).isNull();
  }

  @Test
  void parseNull() {
    assertThat(TraceContext.parse((String) null)).isNull();
  }

  @Test
  void repeatedHeaderIsInvalid() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().add(TraceContext.TRACEPARENT, VALID);
    assertThat(TraceContext.parse(request)).isNotNull();
    request.headers().add(TraceContext.TRACEPARENT, VALID);
    assertThat(TraceContext.parse(request)).isNull();
  }

  @Test
  void generatedIsValidAndSampled() {
    for (int i = 0; i < 100; i++) {
      TraceContext context = TraceContext.generate();
      assertThat(TraceContext.parse(context.header())).isEqualTo(context);
      assertThat(context.flags()).isEqualTo(TraceContext.SAMPLED);
    }
    assertThat(TraceContext.generate().traceId()).isNotEqualTo(TraceContext.generate().traceId());
  }

  @Test
  void childKeepsTraceAndFlags() {
    TraceContext parent = TraceContext.parse(VALID.replace("-01", "-00"));
    TraceContext child = parent.child();
    assertThat(child.traceId()).isEqualTo(parent.traceId());
    assertThat(child.flags()).isEqualTo("00");
    assertThat(child.spanId()).isNotEqualTo(parent.spanId()).hasSize(16);
  }

  @Test
  void randomHexIsNeverZero() {
    for (int i = 0; i < 1_000; i++) {
      assertThat(TraceContext.randomHex(1)).isNotEqualTo("0");
    }
  }
}
