package org.sensepitch.edge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/// Writes one JSON object per request, separated by newlines.
///
/// The keys are the names defined in [OtelLogFields] and [SensepitchLogFields], see
/// [RequestLogFieldsWriter], written
/// flat with dots, e.g. `"http.request.method":"GET"`, so they match the attributes of the OTLP
/// export one to one. The record level fields come first: `timestamp`, `event.name`, `trace_id`
/// and `span_id`. A log collector recognises the records by `event.name` with the value
/// [SensepitchLogFields#EVENT_NAME_VALUE].
///
/// The observed timestamp and the severity of the OTLP record are left out, they are redundant:
/// the observed timestamp is about `timestamp` plus `sensepitch.duration.total_ns`, and the
/// severity follows from `http.response.status_code` and `error.type`.
///
/// The record is rendered into a buffer and written with a single
/// [RequestLogOutput#writeLine(String)] call, so records of concurrent event loops cannot
/// interleave within one line.
///
/// @author Jens Wilke
public class JsonRequestLogger implements RequestLogger {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private static final ProxyLogger DEBUG = ProxyLogger.get(JsonRequestLogger.class);

  private final JsonFactory jsonFactory = new JsonFactory();
  private final RequestLogOutput output;

  public JsonRequestLogger(RequestLogOutput output) {
    this.output = output;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    StringWriter buffer = new StringWriter(1024);
    try (JsonGenerator json = jsonFactory.createGenerator(buffer)) {
      json.writeStartObject();
      RequestLogFieldsWriter.write(info, new Sink(json));
      json.writeEndObject();
    } catch (IOException | UncheckedIOException e) {
      // a StringWriter does not do I/O, so this cannot happen in practice
      DEBUG.error("Error rendering request log record, request " + info.requestId(), e);
      return;
    }
    output.writeLine(buffer.toString());
  }

  static String timestamp(long millis) {
    return TIMESTAMP.format(Instant.ofEpochMilli(millis));
  }

  /// Writes the fields as flat JSON properties. The sink interface does not declare
  /// [IOException], so it is passed on unchecked. Rendering text allocates anyway, so values are
  /// converted to strings where convenient.
  private record Sink(JsonGenerator json) implements RequestLogSink {

    @Override
    public void record(
        long timeMillis,
        long observedTimeMillis,
        RequestLogFieldsWriter.Severity severity,
        String traceId,
        String spanId,
        String traceFlags) {
      try {
        json.writeStringField(OtelLogFields.TIMESTAMP, timestamp(timeMillis));
        json.writeStringField(OtelLogFields.EVENT_NAME, SensepitchLogFields.EVENT_NAME_VALUE);
        if (traceId != null) {
          json.writeStringField(OtelLogFields.TRACE_ID, traceId);
        }
        if (spanId != null) {
          json.writeStringField(OtelLogFields.SPAN_ID, spanId);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /// The summary is for OTLP log viewers, the JSON record has the fields
    @Override
    public void body(String method, String uri, int status) {}

    @Override
    public void string(String key, CharSequence value) {
      try {
        json.writeStringField(key, value.toString());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void string(String key, CharSequence value, int start, int end) {
      string(key, value.subSequence(start, end));
    }

    @Override
    public void address(String key, InetAddress address) {
      string(key, address.getHostAddress());
    }

    @Override
    public void number(String key, long value) {
      try {
        json.writeNumberField(key, value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void bool(String key, boolean value) {
      try {
        json.writeBooleanField(key, value);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void startArray(String key) {
      try {
        json.writeArrayFieldStart(key);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void arrayValue(CharSequence value) {
      try {
        json.writeString(value.toString());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void endArray() {
      try {
        json.writeEndArray();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
