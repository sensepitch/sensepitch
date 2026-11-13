package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
          ExportLogsServiceRequest request =
              ExportLogsServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes());
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
    assertThat(received.poll(10, TimeUnit.SECONDS))
        .as("buffered record handed over and sent by the replacement")
        .isOne();
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

  long dropped(String destination) {
    return (long) metrics.dropped.labelValues(destination).get();
  }

  /// Sum of the records received, waiting until the expected number arrived
  int awaitReceived(int expected) throws InterruptedException {
    int sum = 0;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (sum < expected && System.nanoTime() < deadline) {
      Integer records = received.poll(100, TimeUnit.MILLISECONDS);
      if (records != null) {
        sum += records;
      }
    }
    // anything arriving late would be a duplicate
    Integer more = received.poll(100, TimeUnit.MILLISECONDS);
    return more == null ? sum : sum + more;
  }

  RequestLogDestinationConfig otlpAt(String name, String endpoint, int maxBufferedBytes) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            OtlpConfig.builder()
                .endpoint(endpoint)
                .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
                .maxBufferedBytes(maxBufferedBytes)
                .build())
        .build();
  }

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
  }

  /// The log server is unavailable while a reload changes the destination: the buffered records
  /// move to the replacement and arrive once the server is back. The reload does not wait.
  @Test
  void backlogIsHandedOverDuringAnOutage() throws Exception {
    status = 503;
    logger = RequestLoggerFactory.create(config(otlp("vl", 1)), stdout, metrics);
    for (int i = 0; i < 50; i++) {
      logOneRequest();
    }
    while (attempts.get() == 0) {
      Thread.sleep(5);
    }
    long start = System.nanoTime();
    reload(config(otlp("vl", 2)));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .as("the reload does not wait for the log server")
        .isLessThan(500);
    status = 200;
    assertThat(awaitReceived(50)).isEqualTo(50);
    assertThat(dropped("vl")).isZero();
  }

  /// A reload fixes the endpoint of an unreachable log server, the backlog arrives at the new one
  @Test
  void backlogArrivesAtTheFixedEndpoint() throws Exception {
    int max = OtlpConfig.DEFAULT_MAX_BUFFERED_BYTES;
    logger =
        RequestLoggerFactory.create(
            config(otlpAt("vl", "http://127.0.0.1:1/v1/logs", max)), stdout, metrics);
    for (int i = 0; i < 20; i++) {
      logOneRequest();
    }
    reload(config(otlpAt("vl", endpoint(), max)));
    assertThat(awaitReceived(20)).isEqualTo(20);
    assertThat(dropped("vl")).isZero();
  }

  /// Threads keep logging while reloads replace the destination again and again: every record
  /// arrives exactly once, none is dropped at a switch
  @Test
  void noRecordIsLostWhileReloading() throws Exception {
    logger = RequestLoggerFactory.create(config(otlp("vl", 1)), stdout, metrics);
    int threads = 4;
    int perThread = 300;
    int minReloads = 20;
    List<Thread> workers = new java.util.ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread worker =
          new Thread(
              () -> {
                for (int i = 0; i < perThread; i++) {
                  logOneRequest();
                }
              });
      worker.start();
      workers.add(worker);
    }
    int reloads = 0;
    // with few records the workers may finish early, keep reloading to a minimum count
    for (int i = 0; workers.stream().anyMatch(Thread::isAlive) || reloads < minReloads; i++) {
      reload(config(otlp("vl", 1 + i % 2)));
      reloads++;
      Thread.sleep(1);
    }
    for (Thread worker : workers) {
      worker.join();
    }
    // right after the reloads, replaced destinations may still be handing over
    logger.close();
    int got = awaitReceived(threads * perThread);
    assertThat(dropped("vl")).isZero();
    assertThat(got).as("every record exactly once").isEqualTo(threads * perThread);
    assertThat(reloads).as("the destination was replaced often").isGreaterThan(10);
  }

  /// A replacement with a smaller limit takes over what fits, the oldest surplus is dropped
  @Test
  void takeOverRespectsTheLimit() throws Exception {
    status = 503;
    int big = OtlpConfig.DEFAULT_MAX_BUFFERED_BYTES;
    int small = OtlpConfig.MIN_BATCH_BYTES;
    logger = RequestLoggerFactory.create(config(otlpAt("vl", endpoint(), big)), stdout, metrics);
    int records = 3 * small / 700;
    for (int i = 0; i < records; i++) {
      logOneRequest();
    }
    reload(config(otlpAt("vl", endpoint(), small)));
    status = 200;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    int delivered = 0;
    while (delivered + dropped("vl") < records && System.nanoTime() < deadline) {
      Integer batch = received.poll(100, TimeUnit.MILLISECONDS);
      delivered += batch != null ? batch : 0;
    }
    assertThat(dropped("vl")).as("surplus dropped").isPositive();
    assertThat(delivered + dropped("vl")).isEqualTo(records);
  }

  /// A record logged into the replaced destination after the switch goes to the replacement
  @Test
  void lateRecordIsPassedOn() throws Exception {
    OtlpConfig otlpConfig = OtlpConfig.builder().endpoint(endpoint()).build();
    OtlpRequestLogger old =
        new OtlpRequestLogger(new OtlpLogDestination("old", otlpConfig, metrics));
    OtlpRequestLogger next =
        new OtlpRequestLogger(new OtlpLogDestination("next", otlpConfig, metrics));
    old.handOverTo(next, next);
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    next.close();
    assertThat(awaitReceived(1)).isOne();
    assertThat(dropped("old")).isZero();
    assertThat(dropped("next")).isZero();
  }

  /// A record logged into a replaced destination is passed on without taking a batch there. The
  /// thread would keep that batch as long as it lives, a batch per reload.
  @Test
  void replacedDestinationAllocatesNoBatch() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(60_000).build();
    OtlpRequestLogger old =
        new OtlpRequestLogger(new OtlpLogDestination("old", otlpConfig, metrics));
    OtlpRequestLogger next =
        new OtlpRequestLogger(new OtlpLogDestination("next", otlpConfig, metrics));
    // the replacement sets up the batch of this thread before the measurement
    next.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    old.handOverTo(next, next);
    RequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadAllocatedBytes();
    old.logRequest(info);
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    assertThat(allocated).as("bytes allocated").isLessThan(otlpConfig.effectiveBatchBytes());
    next.close();
    assertThat(awaitReceived(2)).as("both records arrive").isEqualTo(2);
    assertThat(dropped("old")).isZero();
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
