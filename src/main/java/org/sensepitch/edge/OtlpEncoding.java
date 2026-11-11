package org.sensepitch.edge;

/// Field numbers of the OTLP log messages and helpers to encode attributes, see the
/// `opentelemetry/proto/logs/v1/logs.proto` and `common/v1/common.proto` definitions.
///
/// @author Jens Wilke
final class OtlpEncoding {

  private OtlpEncoding() {}

  // ExportLogsServiceRequest
  static final int EXPORT_RESOURCE_LOGS = 1;

  // ResourceLogs
  static final int RESOURCE_LOGS_RESOURCE = 1;
  static final int RESOURCE_LOGS_SCOPE_LOGS = 2;
  static final int RESOURCE_LOGS_SCHEMA_URL = 3;

  // Resource
  static final int RESOURCE_ATTRIBUTES = 1;

  // ScopeLogs
  static final int SCOPE_LOGS_SCOPE = 1;
  static final int SCOPE_LOGS_LOG_RECORDS = 2;
  static final int SCOPE_LOGS_SCHEMA_URL = 3;

  // InstrumentationScope
  static final int SCOPE_NAME = 1;

  // LogRecord
  static final int LOG_TIME_UNIX_NANO = 1;
  static final int LOG_SEVERITY_NUMBER = 2;
  static final int LOG_SEVERITY_TEXT = 3;
  static final int LOG_BODY = 5;
  static final int LOG_ATTRIBUTES = 6;
  static final int LOG_FLAGS = 8;
  static final int LOG_TRACE_ID = 9;
  static final int LOG_SPAN_ID = 10;
  static final int LOG_OBSERVED_TIME_UNIX_NANO = 11;
  static final int LOG_EVENT_NAME = 12;

  // KeyValue
  static final int KEY_VALUE_KEY = 1;
  static final int KEY_VALUE_VALUE = 2;

  // AnyValue
  static final int ANY_STRING = 1;
  static final int ANY_BOOL = 2;
  static final int ANY_INT = 3;
  static final int ANY_ARRAY = 5;

  // ArrayValue
  static final int ARRAY_VALUES = 1;

  private static final long NANOS_PER_MILLI = 1_000_000L;

  static long millisToNanos(long millis) {
    return millis * NANOS_PER_MILLI;
  }

  /// Writes a `KeyValue` with a string value in place, as field `field` of the current message
  static void stringAttribute(
      ProtobufWriter out, int field, String key, CharSequence value, int start, int end) {
    int keyValue = out.startMessage(field);
    out.string(KEY_VALUE_KEY, key);
    int any = out.startMessage(KEY_VALUE_VALUE);
    out.string(ANY_STRING, value, start, end);
    out.endMessage(any);
    out.endMessage(keyValue);
  }

  static void stringAttribute(ProtobufWriter out, int field, String key, CharSequence value) {
    stringAttribute(out, field, key, value, 0, value.length());
  }

  static void intAttribute(ProtobufWriter out, int field, String key, long value) {
    int keyValue = out.startMessage(field);
    out.string(KEY_VALUE_KEY, key);
    int any = out.startMessage(KEY_VALUE_VALUE);
    out.varint(ANY_INT, value);
    out.endMessage(any);
    out.endMessage(keyValue);
  }

  static void boolAttribute(ProtobufWriter out, int field, String key, boolean value) {
    int keyValue = out.startMessage(field);
    out.string(KEY_VALUE_KEY, key);
    int any = out.startMessage(KEY_VALUE_VALUE);
    out.bool(ANY_BOOL, value);
    out.endMessage(any);
    out.endMessage(keyValue);
  }
}
