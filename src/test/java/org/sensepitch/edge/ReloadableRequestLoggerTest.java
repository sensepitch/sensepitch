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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
        .as("buffered record sent by the old destination after the end marker")
        .isOne();
    logOneRequest();
    logger.close();
    assertThat(received.poll(10, TimeUnit.SECONDS)).as("the new destination logs").isOne();
  }

  /// A removed destination finishes after the grace period, nothing is buffered, so its gauge
  /// disappears then
  @Test
  void removedDestinationIsClosed() throws Exception {
    logger =
        RequestLoggerFactory.create(config(stdout(false), otlp("vl", 60_000)), stdout, metrics);
    assertThat(queuedDestinations()).contains("vl");
    reload(config(stdout(true)));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (queuedDestinations().contains("vl") && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(queuedDestinations()).doesNotContain("vl");
    logOneRequest();
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// Switching the request log off ends its destinations, switching it on creates them again
  @Test
  void disableAndEnableByReload() throws Exception {
    logger = RequestLoggerFactory.create(config(otlp("vl", 60_000)), stdout, metrics);
    logOneRequest();
    reload(RequestLogConfig.builder().disabled(true).build());
    assertThat(logger.destinations()).isEmpty();
    assertThat(awaitReceived(1)).as("sent after the end marker").isOne();
    logOneRequest();
    reload(config(otlp("vl", 60_000)));
    logOneRequest();
    logger.close();
    assertThat(awaitReceived(1)).as("only the record after enabling").isOne();
  }

  /// At shutdown, finish also covers a destination still draining from a reload. Until all is
  /// sent, the records not sent are reported by destination.
  @Test
  void finishCoversDrainingDestinations() throws Exception {
    status = 503;
    logger = RequestLoggerFactory.create(config(otlp("old", 60_000)), stdout, metrics);
    logOneRequest();
    reload(config(otlp("new", 60_000)));
    logOneRequest();
    logger.endThread();
    CompletableFuture<Void> finished = logger.finish();
    awaitAttempts(2);
    assertThat(finished).isNotDone();
    assertThat(logger.unsent()).containsExactlyInAnyOrderEntriesOf(Map.of("old", 1, "new", 1));
    status = 200;
    finished.get(10, TimeUnit.SECONDS);
    assertThat(logger.unsent()).isEmpty();
    assertThat(awaitReceived(2)).isEqualTo(2);
  }

  void awaitAttempts(int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (attempts.get() < expected && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(attempts.get()).isGreaterThanOrEqualTo(expected);
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
    assertThat(queuedDestinations())
        .as("the created one is closed, the active one is not")
        .containsExactly("vl");
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

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
  }

  /// The log server is unavailable while a reload changes the destination: the replaced
  /// destination keeps its buffered records and sends them once the server is back. The reload
  /// does not wait.
  @Test
  void replacedDestinationSendsItsBacklog() throws Exception {
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

  /// Removing a destination during an outage does not wait for the log server, the removed
  /// destination sends its records once the server is back
  @Test
  void removedDestinationDoesNotBlockTheReload() throws Exception {
    status = 503;
    logger = RequestLoggerFactory.create(config(otlp("vl", 1)), stdout, metrics);
    for (int i = 0; i < 20; i++) {
      logOneRequest();
    }
    while (attempts.get() == 0) {
      Thread.sleep(5);
    }
    long start = System.nanoTime();
    reload(config(stdout(false)));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .as("the reload does not wait for the log server")
        .isLessThan(500);
    status = 200;
    assertThat(awaitReceived(20)).isEqualTo(20);
    assertThat(dropped("vl")).isZero();
  }

  /// A removed destination whose log server does not come back ends once its records are older
  /// than the maximum age, they are counted as dropped
  @Test
  void finishedDestinationEndsWhenItsRecordsAgedOut() throws Exception {
    status = 503;
    RequestLogDestinationConfig shortAge = otlp("vl", 1);
    shortAge =
        shortAge.toBuilder()
            .otlp(shortAge.otlp().toBuilder().maximumUnsentLogAgeMillis(300).build())
            .build();
    logger = RequestLoggerFactory.create(config(shortAge), stdout, metrics);
    for (int i = 0; i < 10; i++) {
      logOneRequest();
    }
    while (attempts.get() == 0) {
      Thread.sleep(5);
    }
    reload(config(stdout(false)));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (queuedDestinations().contains("vl") && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(queuedDestinations()).as("the destination ended").doesNotContain("vl");
    assertThat(dropped("vl")).isEqualTo(10);
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

  /// A record an event loop logs into a replaced destination after the switch, before it ran its
  /// end marker, is sent by it like its buffered records
  @Test
  void lateRecordGoesToTheOldDestination() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(60_000).build();
    OtlpRequestLogger old =
        new OtlpRequestLogger(new OtlpLogDestination("old", otlpConfig, metrics));
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    CompletableFuture<Void> stopped = old.finish();
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(stopped).as("waits for the end marker").isNotDone();
    old.endThread();
    stopped.get(10, TimeUnit.SECONDS);
    assertThat(awaitReceived(2)).isEqualTo(2);
    assertThat(dropped("old")).isZero();
  }

  /// A record logged into a finished destination is dropped without taking a batch. The thread
  /// would keep that batch as long as it lives, a batch per reload.
  @Test
  void finishedDestinationAllocatesNoBatch() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(60_000).build();
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
        RequestLoggerFactory.create(config(otlp("a", 60_000), otlp("b", 60_000)), stdout, metrics);
    logger.close();
    assertThat(queuedDestinations()).isEmpty();
    logger = null;
  }
}
