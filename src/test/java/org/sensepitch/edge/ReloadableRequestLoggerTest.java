package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Replacing the request log destinations on a configuration reload
///
/// @author Jens Wilke
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReloadableRequestLoggerTest {

  final RequestLogCapture stdout = new RequestLogCapture();
  final RequestLogMetrics metrics = new RequestLogMetrics();

  /// Records received by the OTLP server
  final BlockingQueue<Integer> received = new LinkedBlockingQueue<>();

  HttpServer server;
  ReloadableRequestLogger logger;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/logs",
        exchange -> {
          ExportLogsServiceRequest request =
              ExportLogsServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes());
          received.add(request.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (logger != null) {
      logger.close();
    }
    server.stop(0);
  }

  RequestLogDestinationConfig otlp(String name, int flushIntervalMillis) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            OtlpConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs")
                .flushIntervalMillis(flushIntervalMillis)
                .build())
        .build();
  }

  static RequestLogDestinationConfig stdout(boolean plainText) {
    return RequestLogDestinationConfig.builder().type("stdout").plainText(plainText).build();
  }

  static RequestLogConfig config(RequestLogDestinationConfig... destinations) {
    return RequestLogConfig.builder().destinations(List.of(destinations)).build();
  }

  void reload(RequestLogConfig config) {
    logger.prepare(config).apply();
  }

  void logOneRequest() {
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
  }

  static boolean threadAlive(String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().equals(name));
  }

  /// Destinations with a queue gauge, from the metrics
  List<String> queuedDestinations() {
    PrometheusRegistry registry = new PrometheusRegistry();
    metrics.registerCollectors(registry::register);
    return new MetricsAndMemoryTest.Snapshots(registry.scrape())
        .containsSnapshot("sensepitch_request_log_queued").getDataPoints().stream()
            .map(point -> point.getLabels().get(RequestLogMetrics.DESTINATION_LABEL))
            .toList();
  }

  /// An unchanged destination is kept, an OTLP destination with its buffered records
  @Test
  void unchangedDestinationsAreKept() {
    logger =
        RequestLoggerFactory.create(config(stdout(false), otlp("vl", 60_000)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    logOneRequest();
    reload(config(stdout(false), otlp("vl", 60_000)));
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
    assertThat(received).as("nothing sent, the buffer is kept").isEmpty();
  }

  /// A changed destination is replaced, the old one sends what it buffered when closed
  @Test
  void changedDestinationIsReplaced() throws Exception {
    logger = RequestLoggerFactory.create(config(otlp("vl", 60_000)), stdout, metrics);
    var before = logger.destinations().getFirst();
    logOneRequest();
    reload(config(otlp("vl", 30_000)));
    assertThat(logger.destinations().getFirst()).isNotSameAs(before);
    assertThat(received.poll(10, TimeUnit.SECONDS)).as("buffered record sent on close").isOne();
    logOneRequest();
    logger.close();
    assertThat(received.poll(10, TimeUnit.SECONDS)).as("the new destination logs").isOne();
  }

  /// A removed destination is closed, its thread stops and its gauge disappears
  @Test
  void removedDestinationIsClosed() throws Exception {
    logger =
        RequestLoggerFactory.create(config(stdout(false), otlp("vl", 60_000)), stdout, metrics);
    assertThat(queuedDestinations()).contains("vl");
    reload(config(stdout(true)));
    assertThat(threadAlive("request-log-vl")).isFalse();
    assertThat(queuedDestinations()).doesNotContain("vl");
    logOneRequest();
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// A configuration that fails closes only the destinations created for it, the active ones stay
  @Test
  void failingPrepareKeepsTheActiveDestinations() {
    logger = RequestLoggerFactory.create(config(otlp("vl", 60_000)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    RequestLogDestinationConfig noEndpoint =
        RequestLogDestinationConfig.builder()
            .type("otlp")
            .name("broken")
            .otlp(OtlpConfig.builder().build())
            .build();
    assertThatThrownBy(() -> logger.prepare(config(otlp("vl", 60_000), otlp("new", 1), noEndpoint)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint missing");
    assertThat(threadAlive("request-log-new")).as("created one is closed").isFalse();
    assertThat(threadAlive("request-log-vl")).as("active one is not closed").isTrue();
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
  }

  @Test
  void closeClosesAll() {
    logger =
        RequestLoggerFactory.create(config(otlp("a", 60_000), otlp("b", 60_000)), stdout, metrics);
    logger.close();
    assertThat(threadAlive("request-log-a")).isFalse();
    assertThat(threadAlive("request-log-b")).isFalse();
    logger = null;
  }
}
