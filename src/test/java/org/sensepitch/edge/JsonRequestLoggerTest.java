package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The JSON request log, the default format: flat keys named after the OpenTelemetry semantic
/// conventions. The mapping itself is tested in [RequestLogFieldsWriterTest].
///
/// @author Jens Wilke
class JsonRequestLoggerTest {

  final RequestLogCapture capture = new RequestLogCapture();
  final JsonRequestLogger logger = new JsonRequestLogger(capture);

  JsonNode log(TestRequestLogInfo info) throws Exception {
    logger.logRequest(info);
    assertThat(capture.lines()).hasSize(1);
    return new ObjectMapper().readTree(capture.lines().getFirst());
  }

  @Test
  void readableFieldOrder() throws Exception {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri("/search?q=edge");
    JsonNode record = log(info);
    List<String> keys = new ArrayList<>();
    record.fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .startsWith(
            "timestamp",
            "http.request.method",
            "server.address",
            "url.path",
            "url.query",
            "http.response.status_code",
            "sensepitch.request.outcome",
            "sensepitch.duration.total_ns",
            "http.response.body.size")
        .endsWith("event.name", "trace_id", "span_id")
        .doesNotContain("observed_timestamp", "severity_text", "severity_number");
    assertThat(record.get("timestamp").asText()).isEqualTo("1970-01-01T00:00:00.000Z");
    assertThat(record.get("event.name").asText()).isEqualTo(SensepitchLogFields.EVENT_NAME_VALUE);
    assertThat(record.get("trace_id").asText()).isEqualTo(TestRequestLogInfo.TRACE_ID);
    assertThat(record.get("span_id").asText()).isEqualTo(TestRequestLogInfo.SPAN_ID);
    assertThat(record.has("body")).as("body is OTLP only").isFalse();
  }

  @Test
  void flatDottedKeysWithTypedValues() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.POST, 201).withClientAddress("192.0.2.7");
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "de-DE,de;q=0.9");
    JsonNode record = log(info);
    assertThat(record.get("client.address").asText()).isEqualTo("192.0.2.7");
    assertThat(record.get("http.request.method").asText()).isEqualTo("POST");
    assertThat(record.get("http.response.status_code").isInt()).isTrue();
    assertThat(record.get("http.response.status_code").asInt()).isEqualTo(201);
    assertThat(record.get("sensepitch.request.cookie_present").isBoolean()).isTrue();
    assertThat(record.get("http.request.header.accept-language").toString())
        .isEqualTo("[\"de-DE,de;q=0.9\"]");
    assertThat(record.get("sensepitch.request.header_names").toString())
        .isEqualTo("[\"host\",\"accept-language\"]");
  }

  /// Quotes and control characters in client supplied values are escaped by the JSON encoding.
  @Test
  void clientValuesAreEscaped() throws Exception {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "evil\",\"injected\":\"1");
    JsonNode record = log(info);
    assertThat(record.get("user_agent.original").asText()).isEqualTo("evil\",\"injected\":\"1");
    assertThat(record.has("injected")).isFalse();
  }

  @Test
  void serverErrorHasErrorType() throws Exception {
    JsonNode record = log(TestRequestLogInfo.of(HttpMethod.GET, 504));
    assertThat(record.get("error.type").asText()).isEqualTo("504");
  }
}
