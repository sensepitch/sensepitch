package org.sensepitch.edge;

import static org.sensepitch.edge.OtlpEncoding.ANY_ARRAY;
import static org.sensepitch.edge.OtlpEncoding.ANY_STRING;
import static org.sensepitch.edge.OtlpEncoding.ARRAY_VALUES;
import static org.sensepitch.edge.OtlpEncoding.KEY_VALUE_KEY;
import static org.sensepitch.edge.OtlpEncoding.KEY_VALUE_VALUE;
import static org.sensepitch.edge.OtlpEncoding.LOG_ATTRIBUTES;
import static org.sensepitch.edge.OtlpEncoding.LOG_BODY;
import static org.sensepitch.edge.OtlpEncoding.LOG_EVENT_NAME;
import static org.sensepitch.edge.OtlpEncoding.LOG_FLAGS;
import static org.sensepitch.edge.OtlpEncoding.LOG_OBSERVED_TIME_UNIX_NANO;
import static org.sensepitch.edge.OtlpEncoding.LOG_SEVERITY_NUMBER;
import static org.sensepitch.edge.OtlpEncoding.LOG_SEVERITY_TEXT;
import static org.sensepitch.edge.OtlpEncoding.LOG_SPAN_ID;
import static org.sensepitch.edge.OtlpEncoding.LOG_TIME_UNIX_NANO;
import static org.sensepitch.edge.OtlpEncoding.LOG_TRACE_ID;
import static org.sensepitch.edge.OtlpEncoding.boolAttribute;
import static org.sensepitch.edge.OtlpEncoding.intAttribute;
import static org.sensepitch.edge.OtlpEncoding.millisToNanos;
import static org.sensepitch.edge.OtlpEncoding.stringAttribute;

import java.net.Inet4Address;
import java.net.InetAddress;

/// Encodes the fields of a request as the content of an OTLP `LogRecord` into a
/// [ProtobufWriter], in place and without allocating. The attributes are the fields defined in
/// [RequestLogFieldsWriter], the record level fields map to the log data model. The body is a short
/// summary, e.g. `GET /path 200`.
///
/// Holds reusable state, so an instance is used by one thread at a time, see [ThreadBatcher].
///
/// @author Jens Wilke
final class OtlpRequestLogSink implements RequestLogSink {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /// Reused for text composed from parts: the body and IP addresses
  private final StringBuilder text = new StringBuilder(256);

  private final int sampleHeadersInterval;

  private ProtobufWriter out;
  private int arrayKeyValue;
  private int arrayAny;
  private int arrayValues;

  OtlpRequestLogSink() {
    this(0);
  }

  /// @param sampleHeadersInterval see [RequestLogDestinationConfig#sampleHeadersInterval()]
  OtlpRequestLogSink(int sampleHeadersInterval) {
    this.sampleHeadersInterval = sampleHeadersInterval;
  }

  /// Writes the fields of the `LogRecord` message of the request, the caller writes the
  /// enclosing message
  void encode(ProtobufWriter out, RequestLogInfo info) {
    this.out = out;
    try {
      RequestLogFieldsWriter.write(info, this, sampleHeadersInterval);
    } finally {
      this.out = null;
    }
  }

  /// Encoded `LogRecord` message of the request, for tests
  static byte[] encode(RequestLogInfo info) {
    ProtobufWriter out = new ProtobufWriter(1024);
    new OtlpRequestLogSink().encode(out, info);
    return out.toByteArray();
  }

  @Override
  public void record(
      long timeMillis,
      long observedTimeMillis,
      RequestLogFieldsWriter.Severity severity,
      String traceId,
      String spanId,
      String traceFlags) {
    out.fixed64(LOG_TIME_UNIX_NANO, millisToNanos(timeMillis));
    out.fixed64(LOG_OBSERVED_TIME_UNIX_NANO, millisToNanos(observedTimeMillis));
    out.varint(LOG_SEVERITY_NUMBER, severity.number());
    out.string(LOG_SEVERITY_TEXT, severity.name());
    out.string(LOG_EVENT_NAME, SensepitchLogFields.EVENT_NAME_VALUE);
    if (traceId != null && spanId != null) {
      out.hexBytes(LOG_TRACE_ID, traceId);
      out.hexBytes(LOG_SPAN_ID, spanId);
      if (traceFlags != null) {
        // the lower eight bits are the W3C trace flags
        out.fixed32(LOG_FLAGS, hexByte(traceFlags));
      }
    }
  }

  @Override
  public void body(String method, String uri, int status) {
    text.setLength(0);
    text.append(method).append(' ').append(uri).append(' ').append(status);
    int body = out.startMessage(LOG_BODY);
    out.string(ANY_STRING, text);
    out.endMessage(body);
  }

  @Override
  public void string(String key, CharSequence value) {
    stringAttribute(out, LOG_ATTRIBUTES, key, value);
  }

  @Override
  public void string(String key, CharSequence value, int start, int end) {
    stringAttribute(out, LOG_ATTRIBUTES, key, value, start, end);
  }

  /// Rendered like [InetAddress#getHostAddress()]. An IPv4 address is read from its hash code,
  /// which is the address, so that does not allocate. An IPv6 address is copied by the JDK.
  @Override
  public void address(String key, InetAddress address) {
    text.setLength(0);
    if (address instanceof Inet4Address) {
      int ip = address.hashCode();
      text.append((ip >>> 24) & 0xFF)
          .append('.')
          .append((ip >>> 16) & 0xFF)
          .append('.')
          .append((ip >>> 8) & 0xFF)
          .append('.')
          .append(ip & 0xFF);
    } else {
      // the JDK format: eight groups, lowercase hex without leading zeros, no compression
      byte[] bytes = address.getAddress();
      for (int i = 0; i < bytes.length; i += 2) {
        if (i > 0) {
          text.append(':');
        }
        appendHexGroup(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF));
      }
    }
    stringAttribute(out, LOG_ATTRIBUTES, key, text);
  }

  private void appendHexGroup(int group) {
    boolean leading = true;
    for (int shift = 12; shift >= 0; shift -= 4) {
      int digit = (group >>> shift) & 0xF;
      if (digit != 0 || !leading || shift == 0) {
        text.append(HEX[digit]);
        leading = false;
      }
    }
  }

  @Override
  public void number(String key, long value) {
    intAttribute(out, LOG_ATTRIBUTES, key, value);
  }

  @Override
  public void bool(String key, boolean value) {
    boolAttribute(out, LOG_ATTRIBUTES, key, value);
  }

  @Override
  public void startArray(String key) {
    arrayKeyValue = out.startMessage(LOG_ATTRIBUTES);
    out.string(KEY_VALUE_KEY, key);
    arrayAny = out.startMessage(KEY_VALUE_VALUE);
    arrayValues = out.startMessage(ANY_ARRAY);
  }

  @Override
  public void arrayValue(CharSequence value) {
    int any = out.startMessage(ARRAY_VALUES);
    out.string(ANY_STRING, value);
    out.endMessage(any);
  }

  @Override
  public void endArray() {
    out.endMessage(arrayValues);
    out.endMessage(arrayAny);
    out.endMessage(arrayKeyValue);
  }

  private static int hexByte(String flags) {
    return (Character.digit(flags.charAt(0), 16) << 4) | Character.digit(flags.charAt(1), 16);
  }
}
