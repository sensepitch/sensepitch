package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/// Replacing the request log destinations on a configuration reload. Reloads under concurrent
/// logging and redundant server checks are in [ReloadableRequestLoggerIT], which uses this class
/// as its fixture. New scenarios belong here.
///
/// @author Jens Wilke
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReloadableRequestLoggerTest {

  final RequestLogCapture stdout = new RequestLogCapture();
  final RequestLogMetrics metrics = new RequestLogMetrics();

  /// Records accepted by the OTLP server, per request
  final BlockingQueue<Integer> received = new LinkedBlockingQueue<>();

  /// Status the OTLP server answers with, records are only received with 200
  volatile int status = 200;

  /// Requests the OTLP server got, including refused ones
  final java.util.concurrent.atomic.AtomicInteger attempts =
      new java.util.concurrent.atomic.AtomicInteger();

  HttpServer server;
  ReloadableRequestLogger logger;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/logs",
        exchange -> {
          ExportLogsServiceRequest request = OtlpLogDestinationTest.decode(exchange);
          attempts.incrementAndGet();
          int answer = status;
          if (answer == 200) {
            received.add(request.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          }
          exchange.sendResponseHeaders(answer, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (logger != null) {
      logger.close();
    }
    TestingParameters.stop(server);
  }

  RequestLogDestinationConfig otlp(String name, int flushIntervalMillis) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            OtlpConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs")
                .flushIntervalMillis(flushIntervalMillis)
                // short retries, the outage tests need not wait the production backoff of 1 s
                .initialRetryDelayMillis(10)
                .maxRetryDelayMillis(50)
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
    logger.prepare(config, new ConfigFiles()).apply();
  }

  void logOneRequest() {
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
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
        RequestLoggerFactory.create(
            config(stdout(false), otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    logOneRequest();
    reload(config(stdout(false), otlp("vl", OUT_OF_REACH_MILLIS)));
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
    assertThat(received).as("nothing sent, the buffer is kept").isEmpty();
  }

  /// A destination is kept while its secret file is unchanged, and replaced when the secret
  /// changed, e.g. a rotated token. The files of a kept destination are recorded as well, so the
  /// watcher keeps watching them.
  @Test
  void changedSecretFileReplacesTheDestination(@TempDir Path dir) throws IOException {
    Path token = Files.writeString(dir.resolve("token"), "Bearer one");
    RequestLogDestinationConfig plain = otlp("vl", OUT_OF_REACH_MILLIS);
    RequestLogConfig config =
        config(
            plain.toBuilder()
                .otlp(
                    plain.otlp().toBuilder()
                        .secretHeaders(
                            List.of(
                                SecretHeaderConfig.builder()
                                    .name("Authorization")
                                    .secret(SecretConfig.builder().file(token.toString()).build())
                                    .build()))
                        .build())
                .build());
    ConfigFiles files = new ConfigFiles();
    logger = RequestLoggerFactory.create(config, stdout, metrics, List.of(Runnable::run), files);
    assertThat(files.snapshot().files()).containsOnlyKeys(token.toAbsolutePath());
    var before = logger.destinations().getFirst();
    ConfigFiles unchanged = new ConfigFiles();
    logger.prepare(config, unchanged).apply();
    assertThat(logger.destinations().getFirst()).isSameAs(before);
    assertThat(unchanged.snapshot()).isEqualTo(files.snapshot());
    Path tmp = Files.writeString(dir.resolve("tmp"), "Bearer two");
    Files.move(tmp, token, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    ConfigFiles rotated = new ConfigFiles();
    logger.prepare(config, rotated).apply();
    assertThat(logger.destinations().getFirst()).isNotSameAs(before);
    assertThat(rotated.snapshot()).isNotEqualTo(files.snapshot());
  }

  /// A changed destination is replaced, the old one sends what it buffered when closed
  @Test
  void changedDestinationIsReplaced() throws Exception {
    logger = RequestLoggerFactory.create(config(otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    var before = logger.destinations().getFirst();
    logOneRequest();
    reload(config(otlp("vl", 30_000)));
    assertThat(logger.destinations().getFirst()).isNotSameAs(before);
    assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("buffered record sent by the old destination after the end marker")
        .isOne();
    logOneRequest();
    logger.close();
    assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the new destination logs")
        .isOne();
  }

  /// A removed destination finishes after the grace period, nothing is buffered, so its gauge
  /// disappears then
  @Test
  void removedDestinationIsClosed() throws Exception {
    logger =
        RequestLoggerFactory.create(
            config(stdout(false), otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    assertThat(queuedDestinations()).contains("vl");
    reload(config(stdout(true)));
    await("removed destination ended", () -> !queuedDestinations().contains("vl"));
    assertThat(queuedDestinations()).doesNotContain("vl");
    logOneRequest();
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// At shutdown, finish also covers a destination still draining from a reload. Until all is
  /// sent, the records not sent are reported by destination.
  @Test
  void finishCoversDrainingDestinations() throws Exception {
    status = 503;
    logger = RequestLoggerFactory.create(config(otlp("old", OUT_OF_REACH_MILLIS)), stdout, metrics);
    logOneRequest();
    reload(config(otlp("new", OUT_OF_REACH_MILLIS)));
    logOneRequest();
    logger.endThread();
    CompletableFuture<Void> finished = logger.finish();
    awaitAttempts(2);
    assertThat(finished).isNotDone();
    assertThat(logger.unsent()).containsExactlyInAnyOrderEntriesOf(Map.of("old", 1, "new", 1));
    status = 200;
    finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(logger.unsent()).isEmpty();
    assertThat(awaitReceived(2)).isEqualTo(2);
  }

  void awaitAttempts(int expected) throws Exception {
    await("at least " + expected + " export attempts", () -> attempts.get() >= expected);
  }

  /// A configuration that fails closes only the destinations created for it, the active ones stay
  @Test
  void failingPrepareKeepsTheActiveDestinations() {
    logger = RequestLoggerFactory.create(config(otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    RequestLogDestinationConfig noEndpoint =
        RequestLogDestinationConfig.builder()
            .type("otlp")
            .name("broken")
            .otlp(OtlpConfig.builder().build())
            .build();
    assertThatThrownBy(
            () ->
                logger.prepare(
                    config(otlp("vl", OUT_OF_REACH_MILLIS), otlp("new", 1), noEndpoint),
                    new ConfigFiles()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint missing");
    assertThat(queuedDestinations())
        .as("the created one is closed, the active one is not")
        .containsExactly("vl");
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
  }

  long dropped(String destination) {
    return (long) metrics.dropped.labelValues(destination).get();
  }

  /// Sum of the records received, waiting until the expected number arrived
  int awaitReceived(int expected) throws Exception {
    int[] sum = {0};
    await(
        "at least " + expected + " received records",
        () -> {
          Integer records;
          while (sum[0] < expected && (records = received.poll()) != null) {
            sum[0] += records;
          }
          return sum[0] >= expected;
        });
    // anything arriving late would be a duplicate
    Integer more = received.poll(100, TimeUnit.MILLISECONDS);
    return more == null ? sum[0] : sum[0] + more;
  }

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
  }

  /// A record an event loop logs into a replaced destination after the switch, before it ran its
  /// end marker, is sent by it like its buffered records
  @Test
  void lateRecordGoesToTheOldDestination() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(OUT_OF_REACH_MILLIS).build();
    OtlpRequestLogger old =
        new OtlpRequestLogger(new OtlpLogDestination("old", otlpConfig, metrics));
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    CompletableFuture<Void> stopped = old.finish();
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(stopped).as("waits for the end marker").isNotDone();
    old.endThread();
    stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(awaitReceived(2)).isEqualTo(2);
    assertThat(dropped("old")).isZero();
  }

  /// A record logged into a finished destination is dropped without taking a batch. The thread
  /// would keep that batch as long as it lives, a batch per reload.
  @Test
  void finishedDestinationAllocatesNoBatch() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(OUT_OF_REACH_MILLIS).build();
    OtlpLogDestination destination = new OtlpLogDestination("old", otlpConfig, metrics);
    OtlpRequestLogger old = new OtlpRequestLogger(destination);
    destination.finish();
    RequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadAllocatedBytes();
    old.logRequest(info);
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    assertThat(allocated).as("bytes allocated").isLessThan(otlpConfig.effectiveBatchBytes());
    assertThat(dropped("old")).isOne();
  }

  @Test
  void closeClosesAll() {
    logger =
        RequestLoggerFactory.create(
            config(otlp("a", OUT_OF_REACH_MILLIS), otlp("b", OUT_OF_REACH_MILLIS)),
            stdout,
            metrics);
    logger.close();
    assertThat(queuedDestinations()).isEmpty();
    logger = null;
  }
}
