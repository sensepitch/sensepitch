package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpLogDestinationTest.awaitQuietly;
import static org.sensepitch.edge.OtlpLogDestinationTest.decode;
import static org.sensepitch.edge.OtlpLogDestinationTest.framedSize;
import static org.sensepitch.edge.OtlpLogDestinationTest.request;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// Real HTTP, timer and concurrency checks that repeat coverage of [OtlpLogDestinationTest].
/// New scenarios belong in that unit test, which also supplies this class's server and helpers.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
// alone: captures the log of all threads, the sender logs from its own
@Isolated
class OtlpLogDestinationIT {

  final OtlpLogDestinationTest fixture = new OtlpLogDestinationTest();

  @RegisterExtension LogCapture logs = fixture.logs;

  @BeforeEach
  void startServer() throws IOException {
    fixture.startServer();
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  /// Full batches are posted concurrently, at most `maxConcurrentRequests` at a time
  @Test
  void concurrentRequestsUpToTheLimit() throws Exception {
    java.util.concurrent.atomic.AtomicInteger inFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger maxInFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger records =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    slow.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    slow.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          awaitQuietly(release);
          inFlight.decrementAndGet();
          records.addAndGet(
              decode(exchange, body).getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    slow.start();
    try {
      int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
      fixture.destination =
          new OtlpLogDestination(
              "otlp",
              OtlpConfig.builder()
                  .endpoint("http://127.0.0.1:" + slow.getAddress().getPort() + "/v1/logs")
                  .batchBytes(batchBytes)
                  .maxBufferedBytes(64 << 20)
                  .maxConcurrentRequests(3)
                  .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                  .build());
      OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
      int total = 6 * batchBytes / framedSize(request("/0")) + 1;
      for (int i = 0; i < total; i++) {
        logger.logRequest(request("/" + i));
      }
      await("concurrent request limit reached", () -> inFlight.get() == 3);
      Thread.sleep(100);
      assertThat(maxInFlight.get()).as("requests at the same time").isEqualTo(3);
      release.countDown();
      HttpBatchSenderTest.close(fixture.destination);
      assertThat(records.get()).isEqualTo(total);
      assertThat(fixture.destination.droppedCount()).isZero();
    } finally {
      release.countDown();
      TestingParameters.stop(slow);
    }
  }

  /// During an outage the breaker lets one probe through per backoff, not a retry per batch, and
  /// sends all waiting batches once the server is back
  @Test
  void breakerSendsOneProbePerBackoff() throws Exception {
    fixture.status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    // a backoff of 100 to 200 ms with jitter
    fixture.destination =
        new OtlpLogDestination(
            "otlp",
            fixture
                .config()
                .batchBytes(batchBytes)
                .maxBufferedBytes(64 << 20)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .initialRetryDelayMillis(200)
                .maxRetryDelayMillis(200)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int perBatch = batchBytes / framedSize(request("/0000")) + 1;
    int index = 0;
    for (; index < perBatch; index++) {
      logger.logRequest(request(String.format("/%04d", index)));
    }
    // the first batch failed, the breaker is open
    await("outage retried", () -> fixture.counter("sensepitch_request_log_export_retries") >= 1);
    int total = 11 * perBatch;
    for (; index < total; index++) {
      logger.logRequest(request(String.format("/%04d", index)));
    }
    int before = fixture.received.size();
    Thread.sleep(500);
    assertThat(fixture.received.size() - before)
        .as("probes in 500 ms, with 10 more batches waiting")
        .isLessThanOrEqualTo(6);
    fixture.status = 200;
    await("all records delivered", () -> fixture.deliveredPaths().size() >= total);
    assertThat(new HashSet<>(fixture.deliveredPaths())).hasSize(total);
    assertThat(fixture.destination.droppedCount()).isZero();
  }

  /// The log server is unavailable for a while: first it answers 503, then it is down, then it is
  /// back. Every record arrives exactly once and in order, nothing is dropped.
  @Test
  void outageLosesNothing() throws Exception {
    fixture.status = 503;
    // one request at a time, so the batches arrive in order. The whole backlog must fit: the
    // default limit depends on the number of processors, with two of them records were dropped
    fixture.destination =
        fixture.fastDestination(
            fixture
                .config()
                .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
                .maxBufferedBytes(64 * 1024 * 1024)
                .maxConcurrentRequests(1)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int total = 1_000;
    for (int i = 0; i < total / 2; i++) {
      logger.logRequest(request("/" + i));
    }
    // a few retries answered with 503
    await("three retry attempts received", () -> fixture.received.size() >= 3);
    fixture.server.stop(0);
    for (int i = total / 2; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    // retries against the stopped server
    long retries = fixture.counter("sensepitch_request_log_export_retries");
    await(
        "three retries during outage",
        () -> fixture.counter("sensepitch_request_log_export_retries") >= retries + 3);
    assertThat(fixture.bufferedBytesGauge()).as("backlog waits").isPositive();
    fixture.status = 200;
    fixture.restartServer();
    await("all records delivered", () -> fixture.deliveredPaths().size() >= total);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      expected.add("/" + i);
    }
    assertThat(fixture.deliveredPaths()).as("all, once, in order").isEqualTo(expected);
    assertThat(fixture.destination.droppedCount()).isZero();
    assertThat(fixture.counter("sensepitch_request_log_export_failed")).isZero();
    assertThat(fixture.logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("Buffering records and retrying"))
        .anySatisfy(m -> assertThat(m).contains("recovered after"));
    await("buffered batches released", () -> fixture.bufferedBytesGauge() == 0);
  }

  /// Several threads log at the same time, every record arrives exactly once and intact. While
  /// the sender is busy, the pool provides more batches, so a burst is not dropped.
  @Test
  void concurrentThreads() throws Exception {
    fixture.destination =
        new OtlpLogDestination("otlp", fixture.config().flushIntervalMillis(5).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int threads = 4;
    int perThread = 500;
    List<Thread> started = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int thread = t;
      Thread worker =
          new Thread(
              () -> {
                for (int i = 0; i < perThread; i++) {
                  logger.logRequest(request("/" + thread + "/" + i));
                }
              });
      worker.start();
      started.add(worker);
    }
    for (Thread worker : started) {
      assertThat(worker.join(TIMEOUT)).as("logging worker finished").isTrue();
    }
    HttpBatchSenderTest.close(fixture.destination);
    List<String> paths = fixture.deliveredPaths();
    Set<String> unique = new HashSet<>(paths);
    assertThat(fixture.destination.droppedCount()).isZero();
    assertThat(paths).hasSize(threads * perThread);
    assertThat(unique).as("no record twice").hasSize(threads * perThread);
  }

  /// A server asking for a delay via `Retry-After` is not asked again meanwhile, although the
  /// backoff of this destination is only milliseconds. The exact delay is tested with
  /// [Backoff#delayMillis(int, long)], without waiting. At shutdown the batch gets a last
  /// attempt, regardless of the requested delay, before its records would be dropped.
  @Test
  void retryAfterIsHonoured() throws Exception {
    fixture.answers.add(new OtlpLogDestinationTest.Answer(429, "30"));
    fixture.destination = fixture.fastDestination(fixture.config().build());
    new OtlpRequestLogger(fixture.destination)
        .logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(fixture.receive().status()).isEqualTo(429);
    assertThat(fixture.received.poll(100, TimeUnit.MILLISECONDS))
        .as("no retry before the delay")
        .isNull();
    assertThat(fixture.counter("sensepitch_request_log_export_retries")).isOne();
    HttpBatchSenderTest.close(fixture.destination);
    assertThat(fixture.receive().status()).as("last attempt at shutdown").isEqualTo(200);
    assertThat(fixture.destination.droppedCount()).isZero();
  }
}
