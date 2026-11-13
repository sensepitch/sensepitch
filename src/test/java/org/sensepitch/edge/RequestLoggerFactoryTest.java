package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Configuration of the request log destinations
///
/// @author Jens Wilke
class RequestLoggerFactoryTest {

  final RequestLogCapture stdout = new RequestLogCapture();
  final RequestLogMetrics metrics = new RequestLogMetrics();

  /// Accepts the records of OTLP destinations, so closing a destination does not wait for an
  /// unavailable log server
  HttpServer logServer;

  @BeforeEach
  void startLogServer() throws IOException {
    logServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    logServer.createContext(
        "/v1/logs",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    logServer.start();
  }

  @AfterEach
  void stopLogServer() {
    logServer.stop(0);
  }

  RequestLogger build(RequestLogConfig config) {
    return RequestLoggerFactory.create(config, stdout, metrics);
  }

  /// Destinations with a queue of their own, from the queue length metric
  List<String> queuedDestinations() {
    PrometheusRegistry registry = new PrometheusRegistry();
    metrics.registerCollectors(registry::register);
    return new MetricsAndMemoryTest.Snapshots(registry.scrape())
        .containsSnapshot("sensepitch_request_log_queued").getDataPoints().stream()
            .map(point -> point.getLabels().get(RequestLogMetrics.DESTINATION_LABEL))
            .toList();
  }

  static RequestLogDestinationConfig stdout(boolean plainText, boolean anonymize) {
    return RequestLogDestinationConfig.builder()
        .type("stdout")
        .plainText(plainText)
        .anonymizeClientIp(anonymize)
        .build();
  }

  RequestLogDestinationConfig otlp(String name) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            OtlpConfig.builder()
                .endpoint("http://127.0.0.1:" + logServer.getAddress().getPort() + "/v1/logs")
                .build())
        .build();
  }

  static void logOneRequest(RequestLogger logger) {
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7"));
  }

  /// Without destinations the request log goes to standard out, as in earlier versions
  @Test
  void defaultIsJsonOnStandardOut() {
    try (RequestLogger logger = build(null)) {
      assertThat(((ReloadableRequestLogger) logger).destinations())
          .singleElement()
          .extracting(ReloadableRequestLogger.Destination::logger)
          .isInstanceOf(JsonRequestLogger.class);
      logOneRequest(logger);
    }
    assertThat(queuedDestinations()).isEmpty();
    assertThat(stdout.lines()).singleElement().asString().startsWith("{\"timestamp\"");
  }

  /// The request log can be switched off as a whole, also with destinations configured, e.g. by
  /// a later configuration layer
  @Test
  void disabledRequestLogHasNoDestinations() {
    try (RequestLogger logger =
        build(
            RequestLogConfig.builder()
                .destinations(List.of(stdout(false, false), otlp(null)))
                .disabled(true)
                .build())) {
      assertThat(((ReloadableRequestLogger) logger).destinations()).isEmpty();
      logOneRequest(logger);
    }
    assertThat(queuedDestinations()).isEmpty();
    assertThat(stdout.lines()).isEmpty();
  }

  /// A disabled destination is skipped, the others keep their names
  @Test
  void disabledDestinationIsSkipped() {
    RequestLogDestinationConfig disabledStdout =
        stdout(false, false).toBuilder().disabled(true).build();
    try (RequestLogger logger =
        build(
            RequestLogConfig.builder()
                .destinations(List.of(disabledStdout, stdout(true, false), otlp(null)))
                .build())) {
      assertThat(((ReloadableRequestLogger) logger).destinations())
          .extracting(ReloadableRequestLogger.Destination::name)
          .containsExactly("stdout1", "otlp");
      logOneRequest(logger);
    }
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// All destinations disabled means no request log, it is not rejected like an empty list
  @Test
  void allDestinationsDisabled() {
    RequestLogDestinationConfig disabledStdout =
        stdout(false, false).toBuilder().disabled(true).build();
    try (RequestLogger logger =
        build(RequestLogConfig.builder().destinations(List.of(disabledStdout)).build())) {
      assertThat(((ReloadableRequestLogger) logger).destinations()).isEmpty();
    }
    assertThatThrownBy(() -> build(RequestLogConfig.builder().destinations(List.of()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestLog.disabled");
  }

  @Test
  void legacyPlainTextShorthand() {
    try (RequestLogger logger = build(RequestLogConfig.builder().plainText(true).build())) {
      logOneRequest(logger);
    }
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// The interval of a destination reaches its logger, `1` samples every request
  @Test
  void sampleHeadersInterval() {
    RequestLogDestinationConfig sampling =
        stdout(false, false).toBuilder().sampleHeadersInterval(1).build();
    try (RequestLogger logger =
        build(
            RequestLogConfig.builder()
                .destinations(List.of(stdout(false, false), sampling))
                .build())) {
      logOneRequest(logger);
    }
    assertThat(stdout.lines()).hasSize(2);
    assertThat(stdout.lines().get(0)).doesNotContain("sensepitch.request.sample_headers");
    assertThat(stdout.lines().get(1)).contains("\"sensepitch.request.sample_headers\":[\"");
  }

  @Test
  void negativeSampleHeadersIntervalIsRejected() {
    RequestLogDestinationConfig negative =
        stdout(false, false).toBuilder().sampleHeadersInterval(-1).build();
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(negative)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestLog.destinations[0]: sampleHeadersInterval must not be negative");
  }

  /// Every destination gets the request, each with its own format and anonymization
  @Test
  void multipleDestinations() throws Exception {
    try (RequestLogger logger =
        build(
            RequestLogConfig.builder()
                .destinations(List.of(stdout(false, false), stdout(true, true), otlp(null)))
                .build())) {
      assertThat(queuedDestinations()).containsExactly("otlp");
      logOneRequest(logger);
    }
    OtlpLogDestinationTest.await(() -> queuedDestinations().isEmpty());
    assertThat(stdout.lines()).hasSize(2);
    assertThat(stdout.lines().get(0)).contains("\"client.address\":\"192.0.2.7\"");
    assertThat(stdout.lines().get(1)).startsWith("RQ0 0 example.com 192.0.2.0 ");
  }

  @Test
  void defaultNamesAreNumberedPerType() {
    assertThat(
            RequestLoggerFactory.names(
                List.of(otlp(null), stdout(false, false), stdout(true, false))))
        .containsExactly("otlp", "stdout", "stdout1");
    assertThat(RequestLoggerFactory.names(List.of(otlp("vl"), otlp("collector"))))
        .as("several otlp destinations with names")
        .containsExactly("vl", "collector");
  }

  /// The name identifies an OTLP destination across a reload, a positional default would shift
  /// when one is inserted, so several OTLP destinations must be named
  @Test
  void severalOtlpDestinationsNeedNames() {
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(otlp("vl"), otlp(null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "requestLog.destinations[1]: name required, there are several otlp destinations");
  }

  @Test
  void emptyDestinationsAreRejected() {
    assertThatThrownBy(() -> build(RequestLogConfig.builder().destinations(List.of()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestLog.destinations is empty");
  }

  @Test
  void unknownTypeIsRejected() {
    RequestLogDestinationConfig file = RequestLogDestinationConfig.builder().type("file").build();
    assertThatThrownBy(() -> build(RequestLogConfig.builder().destinations(List.of(file)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown type 'file'");
  }

  @Test
  void duplicateNameIsRejected() {
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(otlp("vl"), otlp("vl"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate name 'vl'");
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(otlp("stdout"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  void otlpSettingsAreMandatoryForOtlp() {
    RequestLogDestinationConfig noSettings =
        RequestLogDestinationConfig.builder().type("otlp").build();
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(stdout(false, false), noSettings)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestLog.destinations[1]: otlp settings missing");
  }

  /// Settings that would be ignored are an error
  @Test
  void otlpSettingsAreRejectedOnStdout() {
    RequestLogDestinationConfig misplaced =
        stdout(false, false).toBuilder()
            .otlp(OtlpConfig.builder().endpoint("http://x").build())
            .build();
    assertThatThrownBy(() -> RequestLoggerFactory.names(List.of(misplaced)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("requestLog.destinations[0]: otlp settings on a stdout destination");
  }

  /// An invalid destination after a valid one stops the valid one, no thread is left behind
  @Test
  void invalidOtlpDestinationIsRejected() {
    RequestLogDestinationConfig noEndpoint =
        RequestLogDestinationConfig.builder()
            .type("otlp")
            .name("broken")
            .otlp(OtlpConfig.builder().build())
            .build();
    assertThatThrownBy(
            () ->
                build(
                    RequestLogConfig.builder()
                        .destinations(List.of(otlp("first"), noEndpoint))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint missing");
    assertThat(queuedDestinations()).as("the created one is closed").doesNotContain("first");
  }

  /// Destinations can be configured via environment variables, by index
  @Test
  void environmentVariables() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_REQUEST_LOG_DESTINATIONS_0_TYPE", "stdout",
            "XX_REQUEST_LOG_DESTINATIONS_1_TYPE", "otlp",
            "XX_REQUEST_LOG_DESTINATIONS_1_OTLP_ENDPOINT",
                "http://victorialogs:9428/insert/opentelemetry/v1/logs",
            "XX_REQUEST_LOG_DESTINATIONS_1_ANONYMIZE_CLIENT_IP", "true",
            "XX_REQUEST_LOG_DESTINATIONS_1_OTLP_HEADERS_0_KEY", "Authorization",
            "XX_REQUEST_LOG_DESTINATIONS_1_OTLP_HEADERS_0_VALUE", "Bearer secret",
            "XX_REQUEST_LOG_DESTINATIONS_1_OTLP_HEADERS_1_KEY", "VL-Stream-Fields",
            "XX_REQUEST_LOG_DESTINATIONS_1_OTLP_HEADERS_1_VALUE", "service.name,server.address",
            "XX_TRACE_CONTEXT_TRUST_TRACEPARENT", "true");
    ProxyConfig config = (ProxyConfig) EnvInjector.injectFromEnv("XX_", env, ProxyConfig.builder());
    List<RequestLogDestinationConfig> destinations = config.requestLog().destinations();
    assertThat(destinations).hasSize(2);
    assertThat(destinations.get(0).type()).isEqualTo("stdout");
    assertThat(destinations.get(1).type()).isEqualTo("otlp");
    assertThat(destinations.get(1).anonymizeClientIp()).isTrue();
    OtlpConfig otlp = destinations.get(1).otlp();
    assertThat(otlp.endpoint()).isEqualTo("http://victorialogs:9428/insert/opentelemetry/v1/logs");
    assertThat(otlp.headers())
        .containsExactly(
            Map.entry("Authorization", "Bearer secret"),
            Map.entry("VL-Stream-Fields", "service.name,server.address"));
    assertThat(config.traceContext().trustTraceparent()).isTrue();
  }

  @Test
  void environmentVariableMapEntryNeedsValue() {
    Map<String, String> env =
        Map.of("XX_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_KEY", "Authorization");
    assertThatThrownBy(() -> EnvInjector.injectFromEnv("XX_", env, ProxyConfig.builder()))
        .hasMessageContaining("Missing env var XX_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_VALUE");
  }

  @Test
  void configDefaults() {
    OtlpConfig config = OtlpConfig.builder().build();
    assertThat(config.effectiveTimeoutMillis()).isEqualTo(10_000);
    assertThat(config.effectiveFlushIntervalMillis()).isEqualTo(1_000);
    assertThat(config.effectiveBatchBytes()).isEqualTo(1024 * 1024);
    assertThat(config.effectiveMaxBufferedBytes())
        .isEqualTo(2L * OtlpConfig.defaultEventLoopThreads() * 1024 * 1024);
  }
}
