package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// The request log fields, named after the OpenTelemetry semantic conventions
///
/// @author Jens Wilke
class RequestLogFieldsWriterTest {

  /// Record level fields as the sink received them, plus the event name and the composed body
  record Record(
      long timeMillis,
      long observedTimeMillis,
      String eventName,
      RequestLogFieldsWriter.Severity severity,
      String traceId,
      String spanId,
      String traceFlags,
      String body) {}

  /// Collects the fields in a map, keys in emission order. Ranges become substrings, addresses
  /// strings and arrays lists.
  static class RecordingSink implements RequestLogSink {

    Record record;
    final Map<String, Object> fields = new LinkedHashMap<>();
    private String arrayKey;
    private List<String> array;

    @Override
    public void record(
        long timeMillis,
        long observedTimeMillis,
        RequestLogFieldsWriter.Severity severity,
        String traceId,
        String spanId,
        String traceFlags) {
      assertThat(this.record).as("record level fields are emitted once, first").isNull();
      assertThat(fields).isEmpty();
      this.record =
          new Record(
              timeMillis,
              observedTimeMillis,
              SensepitchLogFields.EVENT_NAME_VALUE,
              severity,
              traceId,
              spanId,
              traceFlags,
              null);
    }

    @Override
    public void body(String method, String uri, int status) {
      assertThat(fields).as("body follows the record level fields").isEmpty();
      record =
          new Record(
              record.timeMillis(),
              record.observedTimeMillis(),
              record.eventName(),
              record.severity(),
              record.traceId(),
              record.spanId(),
              record.traceFlags(),
              method + " " + uri + " " + status);
    }

    @Override
    public void string(String key, CharSequence value) {
      put(key, value.toString());
    }

    @Override
    public void string(String key, CharSequence value, int start, int end) {
      put(key, value.subSequence(start, end).toString());
    }

    @Override
    public void address(String key, InetAddress address) {
      put(key, address.getHostAddress());
    }

    @Override
    public void number(String key, long value) {
      put(key, value);
    }

    @Override
    public void bool(String key, boolean value) {
      put(key, value);
    }

    @Override
    public void startArray(String key) {
      assertThat(arrayKey).as("arrays do not nest").isNull();
      arrayKey = key;
      array = new ArrayList<>();
    }

    @Override
    public void arrayValue(CharSequence value) {
      assertThat(arrayKey).as("value within an array").isNotNull();
      array.add(value.toString());
    }

    @Override
    public void endArray() {
      put(arrayKey, List.copyOf(array));
      arrayKey = null;
    }

    private void put(String key, Object value) {
      assertThat(fields).as("key emitted once: " + key).doesNotContainKey(key);
      fields.put(key, value);
    }
  }

  static RecordingSink fields(RequestLogInfo info) {
    RecordingSink sink = new RecordingSink();
    RequestLogFieldsWriter.write(info, sink);
    return sink;
  }

  @Test
  void recordLevelFields() {
    long before = System.currentTimeMillis();
    Record record = fields(TestRequestLogInfo.of(HttpMethod.GET, 200)).record;
    assertThat(record.timeMillis()).isZero();
    assertThat(record.observedTimeMillis()).isGreaterThanOrEqualTo(before);
    assertThat(record.eventName()).isEqualTo("sensepitch.request");
    assertThat(record.severity()).isEqualTo(RequestLogFieldsWriter.Severity.INFO);
    assertThat(record.traceId()).isEqualTo(TestRequestLogInfo.TRACE_ID);
    assertThat(record.spanId()).isEqualTo(TestRequestLogInfo.SPAN_ID);
    assertThat(record.traceFlags()).isEqualTo("01");
    assertThat(record.body()).isEqualTo("GET / 200");
  }

  @Test
  void semanticConventionFields() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.POST, 201)
            .withClientAddress("192.0.2.7")
            .withTls("TLSv1.3");
    info.request().setUri("/search?q=edge");
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "curl/8");
    info.request().headers().set(HttpHeaderNames.REFERER, "https://example.org/");
    info.request().headers().set(RequestLogFieldsWriter.SEC_CH_UA_MOBILE, "?0");
    info.request().headers().set(RequestLogFieldsWriter.SIGNATURE_AGENT, "agent.example");
    info.request().headers().set(IpTraitsHandler.COUNTRY_HEADER, "DE");
    Map<String, Object> fields = fields(info).fields;
    assertThat(fields)
        .containsEntry("client.address", "192.0.2.7")
        .containsEntry("client.port", 4711L)
        .containsEntry("server.address", "example.com")
        .containsEntry("server.port", 443L)
        .containsEntry("geo.country.iso_code", "DE")
        .containsEntry("http.request.method", "POST")
        .containsEntry("url.path", "/search")
        .containsEntry("url.query", "q=edge")
        .containsEntry("url.scheme", "https")
        .containsEntry("network.protocol.name", "http")
        .containsEntry("network.protocol.version", "1.1")
        .containsEntry("network.transport", "tcp")
        .containsEntry("tls.protocol.name", "tls")
        .containsEntry("tls.protocol.version", "1.3")
        .containsEntry("tls.cipher", "TLS_AES_128_GCM_SHA256")
        .containsEntry("http.response.status_code", 201L)
        .containsEntry("http.request.body.size", 7L)
        .containsEntry("http.response.body.size", 3L)
        .containsEntry("http.request.size", 50L)
        .containsEntry("http.response.size", 100L)
        .containsEntry("user_agent.original", "curl/8")
        .containsEntry("http.request.header.referer", List.of("https://example.org/"))
        .containsEntry("http.request.header.sec-ch-ua-mobile", List.of("?0"))
        .containsEntry("http.request.header.signature-agent", List.of("agent.example"))
        .doesNotContainKeys("http.request.method_original", "error.type", "exception.type");
  }

  @Test
  void sensepitchFields() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(IpTraitsHandler.TRAITS_HEADER, "asn=64496, country=DE");
    info.request().headers().set(IpTraitsHandler.ASN_HEADER, "64496");
    info.request()
        .headers()
        .set(Ja4HeaderHandler.JA4_HEADER, "t13d1516h2_8daaf6152771_02713d6af862");
    info.request().headers().set(Deflector.ADMISSION_TOKEN_HEADER, "token");
    info.request().headers().set(BypassCheck.HEADER, "uri");
    info.request().headers().set(Deflector.TRAFFIC_TYPE_HEADER, "human");
    info.request().headers().set(HttpHeaderNames.COOKIE, "a=b");
    Map<String, Object> fields = fields(info).fields;
    assertThat(fields)
        .containsEntry("sensepitch.connection.id", "0")
        .containsEntry("sensepitch.connection.request_count", 0L)
        .containsEntry("sensepitch.ip_traits", "asn=64496, country=DE")
        .containsEntry("sensepitch.client.asn", 64496L)
        .containsEntry("sensepitch.tls.client.ja4", "t13d1516h2_8daaf6152771_02713d6af862")
        .containsEntry("sensepitch.protection.admission_token", "token")
        .containsEntry("sensepitch.protection.bypass", "uri")
        .containsEntry("sensepitch.traffic.type", "human")
        .containsEntry("sensepitch.request.cookie_present", true)
        .containsEntry("sensepitch.duration.receive_ns", 1_000L)
        .containsEntry("sensepitch.duration.response_ns", 2_000L)
        .containsEntry("sensepitch.duration.total_ns", 3_000L);
    assertThat(fields.get("sensepitch.request.header_names"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .startsWith("host")
        .contains("cookie");
  }

  /// Fields for operations: why the request ended, the route, the configuration and the
  /// protection
  @Test
  void operationalFields() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 503)
            .withOutcome(RequestOutcome.FALLBACK, 503)
            .withRoute("/api/*")
            .withConfigId("0123456789ab")
            .withProtectionOutcome("passed");
    info.response()
        .headers()
        .set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, "text/html");
    assertThat(fields(info).fields)
        .containsEntry("sensepitch.request.outcome", "fallback")
        .containsEntry("sensepitch.upstream.status", 503L)
        .containsEntry("http.route", "/api/*")
        .containsEntry("sensepitch.config.id", "0123456789ab")
        .containsEntry("sensepitch.protection.outcome", "passed")
        .containsEntry("http.response.header.content-type", List.of("text/html"));
  }

  /// The outcome is always present, it is never absent like optional fields
  @Test
  void outcomeIsAlwaysPresent() {
    assertThat(fields(TestRequestLogInfo.of(HttpMethod.GET, 200)).fields)
        .containsEntry("sensepitch.request.outcome", "completed");
  }

  /// The upstream that served the request, absent when the edge answered itself
  @Test
  void upstream() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200)
            .withUpstream("sites.example.com.paths./api/*.upstream", "backend:8080");
    assertThat(fields(info).fields)
        .containsEntry("sensepitch.upstream.name", "sites.example.com.paths./api/*.upstream")
        .containsEntry("sensepitch.upstream.target", "backend:8080");
    TestRequestLogInfo response =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withUpstream("sites.example.com.response", null);
    assertThat(fields(response).fields)
        .containsEntry("sensepitch.upstream.name", "sites.example.com.response")
        .doesNotContainKey("sensepitch.upstream.target");
  }

  /// Absent values are left out, there are no placeholders
  @Test
  void absentValuesAreLeftOut() {
    Map<String, Object> fields = fields(TestRequestLogInfo.of(HttpMethod.GET, 200)).fields;
    assertThat(fields)
        .containsEntry("url.path", "/")
        .containsEntry("url.scheme", "http")
        .doesNotContainKeys(
            "client.address",
            "client.port",
            "url.query",
            "tls.protocol.name",
            "tls.cipher",
            "user_agent.original",
            "geo.country.iso_code",
            "sensepitch.client.asn",
            "sensepitch.upstream.name",
            "sensepitch.upstream.target",
            "sensepitch.upstream.status",
            "http.route",
            "sensepitch.config.id",
            "sensepitch.protection.outcome",
            "http.response.header.content-type",
            "http.request.header.traceparent");
  }

  @Test
  void emptyQueryIsKept() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri("/path?");
    Map<String, Object> fields = fields(info).fields;
    assertThat(fields).containsEntry("url.path", "/path").containsEntry("url.query", "");
  }

  @Test
  void noQueryIsLeftOut() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri("/path");
    Map<String, Object> fields = fields(info).fields;
    assertThat(fields).containsEntry("url.path", "/path").doesNotContainKey("url.query");
  }

  /// The conventions limit the method to known values, to keep the cardinality low
  @Test
  void unknownMethod() {
    Map<String, Object> fields =
        fields(TestRequestLogInfo.of(HttpMethod.valueOf("PROPFIND"), 200)).fields;
    assertThat(fields)
        .containsEntry("http.request.method", "_OTHER")
        .containsEntry("http.request.method_original", "PROPFIND");
  }

  /// A request that was never received has no protocol version
  @Test
  void mockRequestHasNoProtocol() {
    TestRequestLogInfo info = TestRequestLogInfo.of(RequestLogInfo.NIL_METHOD, 408);
    info.request().setProtocolVersion(RequestLogInfo.NIL_VERSION);
    Map<String, Object> fields = fields(info).fields;
    assertThat(fields)
        .containsEntry("http.request.method", "_OTHER")
        .doesNotContainKeys("network.protocol.name", "network.protocol.version");
  }

  @Test
  void exception() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 502)
            .withError(new IllegalStateException("upstream gone"));
    RecordingSink sink = fields(info);
    assertThat(sink.record.severity()).isEqualTo(RequestLogFieldsWriter.Severity.WARN);
    assertThat(sink.fields)
        .containsEntry("error.type", "java.lang.IllegalStateException")
        .containsEntry("exception.type", "java.lang.IllegalStateException")
        .containsEntry("exception.message", "upstream gone");
  }

  @Test
  void exceptionWithoutMessage() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withError(new IllegalStateException());
    RecordingSink sink = fields(info);
    assertThat(sink.record.severity()).isEqualTo(RequestLogFieldsWriter.Severity.WARN);
    assertThat(sink.fields)
        .containsEntry("error.type", "java.lang.IllegalStateException")
        .doesNotContainKey("exception.message");
  }

  /// A server error without exception, e.g. a timeout response, has the status as error type
  @Test
  void serverErrorWithoutException() {
    RecordingSink sink = fields(TestRequestLogInfo.of(HttpMethod.GET, 504));
    assertThat(sink.record.severity()).isEqualTo(RequestLogFieldsWriter.Severity.WARN);
    assertThat(sink.fields).containsEntry("error.type", "504").doesNotContainKey("exception.type");
  }

  @Test
  void clientTraceparentIsLogged() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    String header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    info.request().headers().set(TraceContext.TRACEPARENT, header);
    assertThat(fields(info).fields)
        .containsEntry("http.request.header.traceparent", List.of(header));
  }

  @Test
  void asnThatIsNotANumberIsAString() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(IpTraitsHandler.ASN_HEADER, "unknown");
    assertThat(fields(info).fields).containsEntry("sensepitch.client.asn", "unknown");
  }

  @Test
  void protocolVersions() {
    TestRequestLogInfo http10 = TestRequestLogInfo.of(HttpMethod.GET, 200);
    http10.request().setProtocolVersion(io.netty.handler.codec.http.HttpVersion.HTTP_1_0);
    assertThat(fields(http10).fields)
        .containsEntry("network.protocol.name", "http")
        .containsEntry("network.protocol.version", "1.0");
    TestRequestLogInfo other = TestRequestLogInfo.of(HttpMethod.GET, 200);
    other.request().setProtocolVersion(io.netty.handler.codec.http.HttpVersion.valueOf("SPDY/3.1"));
    assertThat(fields(other).fields)
        .containsEntry("network.protocol.name", "spdy")
        .containsEntry("network.protocol.version", "3.1");
  }

  /// Status codes beyond the registered range are rendered on demand
  @Test
  void unusualServerErrorStatus() {
    assertThat(fields(TestRequestLogInfo.of(HttpMethod.GET, 599)).fields)
        .containsEntry("error.type", "599");
    assertThat(fields(TestRequestLogInfo.of(HttpMethod.GET, 600)).fields)
        .containsEntry("error.type", "600");
  }

  @Test
  void sslProtocolName() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200).withTls("SSLv3");
    assertThat(fields(info).fields)
        .containsEntry("tls.protocol.name", "ssl")
        .containsEntry("tls.protocol.version", "3");
  }

  /// The optional methods of the sink leave out absent values and pass present ones on
  @Test
  void optionalSinkMethods() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.optionalString("absent", null);
    sink.optionalAddress("absent.address", null);
    sink.optionalNumber("zero", 0);
    sink.optionalNumber("negative", -1);
    sink.optionalString("present", "x");
    sink.optionalAddress("present.address", java.net.InetAddress.getByName("192.0.2.7"));
    sink.optionalNumber("positive", 443);
    assertThat(sink.fields)
        .containsExactly(
            Map.entry("present", "x"),
            Map.entry("present.address", "192.0.2.7"),
            Map.entry("positive", 443L));
  }

  /// The sampled headers keeps order, casing and repeated headers, and leaves out secrets and
  /// the headers the edge adds
  @Test
  void sampleHeaders() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().add("User-Agent", "curl/8");
    info.request().headers().add("Cookie", "sid=secret");
    info.request().headers().add("Accept", "text/html");
    info.request().headers().add("Accept", "*/*");
    info.request().headers().add("Authorization", "Bearer secret");
    info.request().headers().add("Proxy-Authorization", "Basic secret");
    info.request().headers().add(TraceContext.TRACEPARENT, "00-x-y-01");
    info.request().headers().add("X-Forwarded-For", "192.0.2.7");
    info.request().headers().add("X-Forwarded-Proto", "https");
    info.request().headers().add(IpTraitsHandler.COUNTRY_HEADER, "DE");
    info.request().headers().add(Ja4HeaderHandler.JA4_RAW_HEADER, "raw");
    info.request().headers().add(Deflector.ADMISSION_TOKEN_HEADER, "token");
    info.request().headers().add(BypassCheck.HEADER, "uri");
    info.request().headers().add(Deflector.TRAFFIC_TYPE_HEADER, "human");
    info.request().headers().add("sp-future-header", "x");
    info.request().headers().add("X-Custom", "1");
    RecordingSink sink = new RecordingSink();
    RequestLogFieldsWriter.write(info, sink, 1);
    assertThat(sink.fields.get("sensepitch.request.sample_headers"))
        .isEqualTo(
            List.of(
                "host: example.com",
                "User-Agent: curl/8",
                "Accept: text/html",
                "Accept: */*",
                "X-Custom: 1"));
    assertThat(fields(info).fields).doesNotContainKey("sensepitch.request.sample_headers");
  }
}
