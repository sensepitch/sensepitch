package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpRequest;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/// W3C Trace Context of a request, see the `traceparent` header specification.
///
/// OpenTelemetry has no request id attribute; requests are correlated by trace id and span id.
/// The edge assigns every request a span id of its own, which serves as the request id in the
/// request log and is sent to upstream, so backend logs and traces can be correlated with the edge.
///
/// @param traceId 32 lowercase hex characters, not all zero
/// @param spanId 16 lowercase hex characters, not all zero
/// @param flags 2 lowercase hex characters, `01` means sampled
/// @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
/// @author Jens Wilke
public record TraceContext(String traceId, String spanId, String flags) {

  /// Name of the request header carrying the trace context
  public static final String TRACEPARENT = "traceparent";

  /// Name of the request header carrying vendor specific trace state
  public static final String TRACESTATE = "tracestate";

  /// Flags of a trace started by the edge. The edge decides "sampled", so backends with a parent
  /// based sampler record the traces it starts.
  static final String SAMPLED = "01";

  private static final int TRACE_ID_LENGTH = 32;
  private static final int SPAN_ID_LENGTH = 16;

  /// Length of a version `00` header: `vv-<trace id>-<span id>-ff`
  private static final int HEADER_LENGTH = 2 + 1 + TRACE_ID_LENGTH + 1 + SPAN_ID_LENGTH + 1 + 2;

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /// Starts a new trace with random ids, flagged as sampled
  public static TraceContext generate() {
    return new TraceContext(randomHex(TRACE_ID_LENGTH), randomHex(SPAN_ID_LENGTH), SAMPLED);
  }

  /// Context of a child span: same trace and flags, new span id
  public TraceContext child() {
    return new TraceContext(traceId, randomHex(SPAN_ID_LENGTH), flags);
  }

  /// The value of the `traceparent` header, version `00`
  public String header() {
    return "00-" + traceId + "-" + spanId + "-" + flags;
  }

  /// Parses a `traceparent` header value.
  ///
  /// A version higher than `00` is accepted when its first fields have the version `00` layout,
  /// as the specification demands. Versions are forward compatible, any data after the flags is
  /// ignored.
  ///
  /// @return the parsed context, or `null` if the header is missing or invalid
  public static TraceContext parse(String header) {
    if (header == null || header.length() < HEADER_LENGTH) {
      return null;
    }
    String version = header.substring(0, 2);
    if (!isHex(version) || version.equals("ff")) {
      return null;
    }
    if (version.equals("00") ? header.length() != HEADER_LENGTH : !isFutureLayout(header)) {
      return null;
    }
    if (header.charAt(2) != '-'
        || header.charAt(3 + TRACE_ID_LENGTH) != '-'
        || header.charAt(4 + TRACE_ID_LENGTH + SPAN_ID_LENGTH) != '-') {
      return null;
    }
    String traceId = header.substring(3, 3 + TRACE_ID_LENGTH);
    String spanId = header.substring(4 + TRACE_ID_LENGTH, 4 + TRACE_ID_LENGTH + SPAN_ID_LENGTH);
    String flags = header.substring(HEADER_LENGTH - 2, HEADER_LENGTH);
    if (!isHex(traceId) || !isHex(spanId) || !isHex(flags) || isZero(traceId) || isZero(spanId)) {
      return null;
    }
    return new TraceContext(traceId, spanId, flags);
  }

  /// Parses the `traceparent` header of a request. More than one header is invalid.
  ///
  /// @return the parsed context, or `null` if the header is missing, repeated or invalid
  public static TraceContext parse(HttpRequest request) {
    List<String> values = request.headers().getAll(TRACEPARENT);
    if (values.size() != 1) {
      return null;
    }
    return parse(values.getFirst());
  }

  /// A future version may append fields, separated by a dash
  private static boolean isFutureLayout(String header) {
    return header.length() == HEADER_LENGTH || header.charAt(HEADER_LENGTH) == '-';
  }

  /// Lowercase hex digits only, the specification does not allow uppercase
  private static boolean isHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isZero(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != '0') {
        return false;
      }
    }
    return true;
  }

  /// Random lowercase hex string that is not all zero, which would be invalid
  static String randomHex(int length) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    char[] chars = new char[length];
    boolean zero = true;
    do {
      for (int i = 0; i < length; i++) {
        int digit = random.nextInt(16);
        zero &= digit == 0;
        chars[i] = HEX[digit];
      }
    } while (zero);
    return new String(chars);
  }
}
