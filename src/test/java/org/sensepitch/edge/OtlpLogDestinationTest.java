package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/// OTLP/HTTP export of the request log: buffering, batching and retry. The posted bytes are parsed
/// with the official OTLP message classes, which proves the hand written encoding conforms to the
/// schema.
///
/// @author Jens Wilke
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OtlpLogDestinationTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  /// A received POST and the status it was answered with
  record Received(
      String contentType,
      Map<String, String> headers,
      ExportLogsServiceRequest body,
      int status,
      long nanoTime) {

    int records() {
      return body.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount();
    }

    List<String> paths() {
      return body.getResourceLogs(0).getScopeLogs(0).getLogRecordsList().stream()
          .map(record -> attributes(record.getAttributesList()).get("url.path").getStringValue())
          .toList();
    }
  }

  final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

  /// Scripted answer of the log server
  ///
  /// @param retryAfter value of the `Retry-After` header, or `null`
  record Answer(int status, String retryAfter) {}

  /// Answers for the next requests, then [#status] is used
  final BlockingQueue<Answer> answers = new LinkedBlockingQueue<>();

  volatile int status = 200;

  HttpServer server;
  int port;
  OtlpLogDestination destination;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    startServerOn(server);
  }

  void startServerOn(HttpServer server) {
    server.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          Map<String, String> headers =
              exchange.getRequestHeaders().entrySet().stream()
                  .collect(
                      Collectors.toMap(
                          e -> e.getKey().toLowerCase(), e -> e.getValue().getFirst()));
          Answer scripted = answers.poll();
          Answer answer = scripted != null ? scripted : new Answer(status, null);
          received.add(
              new Received(
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  headers,
                  ExportLogsServiceRequest.parseFrom(body),
                  answer.status(),
                  System.nanoTime()));
          if (answer.retryAfter() != null) {
            exchange.getResponseHeaders().set("Retry-After", answer.retryAfter());
          }
          exchange.sendResponseHeaders(answer.status(), -1);
          exchange.close();
        });
    server.start();
  }

  /// The log server comes back on the same port after a restart
  void restartServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    startServerOn(server);
  }

  @AfterEach
  void stop() {
    if (destination != null) {
      destination.close();
    }
    server.stop(0);
  }

  String endpoint() {
    return "http://127.0.0.1:" + port + "/v1/logs";
  }

  OtlpConfig.Builder config() {
    return OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(1);
  }

  /// Destination with short retry delays and close timeout, to keep the tests fast
  OtlpLogDestination fastDestination(OtlpConfig config) {
    return new OtlpLogDestination("otlp", config, new RequestLogMetrics(), 10, 50, 1_000);
  }

  static Map<String, AnyValue> attributes(List<KeyValue> keyValues) {
    return keyValues.stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
  }

  static TestRequestLogInfo request(String path) {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri(path);
    return info;
  }

  /// Size of a record of the info, as framed in a batch
  static int framedSize(RequestLogInfo info) {
    int size = OtlpRequestLogSink.encode(info).length;
    return 1 + ProtobufWriter.varintSize(size) + size;
  }

  /// Records of all requests answered with success, in the order received
  List<String> deliveredPaths() {
    List<String> paths = new ArrayList<>();
    for (Received post : received) {
      if (post.status() / 100 == 2) {
        paths.addAll(post.paths());
      }
    }
    return paths;
  }

  long counter(String name) {
    PrometheusRegistry registry = new PrometheusRegistry();
    destination.metrics().registerCollectors(registry::register);
    return (long)
        new MetricsAndMemoryTest.Snapshots(registry.scrape())
            .counter(name, "destination", destination.destination())
            .getValue();
  }

  /// Waits for a condition the sender reaches asynchronously, e.g. after the response to the last
  /// batch, which the server sees before the sender
  static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 10 seconds");
      }
      Thread.sleep(5);
    }
  }

  long bufferedBytesGauge() {
    PrometheusRegistry registry = new PrometheusRegistry();
    destination.metrics().registerCollectors(registry::register);
    return (long)
        new MetricsAndMemoryTest.Snapshots(registry.scrape())
            .gauge(
                "sensepitch_request_log_buffered_bytes", "destination", destination.destination())
            .getValue();
  }

  @Test
  void recordIsExportedWithResourceScopeAndAttributes() throws Exception {
    destination =
        new OtlpLogDestination(
            "otlp",
            config()
                .headers(Map.of("VL-Stream-Fields", "service.name"))
                .resourceAttributes(Map.of("deployment.environment", "test"))
                .build());
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7");
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "en");
    new OtlpRequestLogger(destination).logRequest(info);
    Received post = received.take();
    assertThat(post.contentType()).isEqualTo("application/x-protobuf");
    assertThat(post.headers()).containsEntry("vl-stream-fields", "service.name");

    ResourceLogs resourceLogs = post.body().getResourceLogs(0);
    assertThat(resourceLogs.getSchemaUrl()).isEqualTo(OtelLogFields.SCHEMA_URL);
    Map<String, AnyValue> resource = attributes(resourceLogs.getResource().getAttributesList());
    assertThat(resource.get("service.name").getStringValue()).isEqualTo("sensepitch-edge");
    assertThat(resource.get("deployment.environment").getStringValue()).isEqualTo("test");

    ScopeLogs scopeLogs = resourceLogs.getScopeLogs(0);
    assertThat(scopeLogs.getScope().getName()).isEqualTo(OtlpLogDestination.SCOPE);
    assertThat(scopeLogs.getSchemaUrl()).isEqualTo(OtelLogFields.SCHEMA_URL);
    LogRecord record = scopeLogs.getLogRecords(0);
    assertThat(record.getTimeUnixNano()).isZero();
    assertThat(record.getObservedTimeUnixNano()).isPositive();
    assertThat(record.getSeverityNumberValue()).isEqualTo(9);
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getEventName()).isEqualTo(SensepitchLogFields.EVENT_NAME_VALUE);
    assertThat(record.getBody().getStringValue()).isEqualTo("GET / 200");
    assertThat(HexFormat.of().formatHex(record.getTraceId().toByteArray()))
        .isEqualTo(TestRequestLogInfo.TRACE_ID);
    assertThat(HexFormat.of().formatHex(record.getSpanId().toByteArray()))
        .isEqualTo(TestRequestLogInfo.SPAN_ID);
    assertThat(record.getFlags()).isEqualTo(1);

    Map<String, AnyValue> attributes = attributes(record.getAttributesList());
    assertThat(attributes.get("client.address").getStringValue()).isEqualTo("192.0.2.7");
    assertThat(attributes.get("http.response.status_code").getIntValue()).isEqualTo(200);
    assertThat(attributes.get("sensepitch.request.cookie_present").getBoolValue()).isFalse();
    assertThat(
            attributes.get("http.request.header.accept-language").getArrayValue().getValuesList())
        .extracting(AnyValue::getStringValue)
        .containsExactly("en");
  }

  /// Exactly the attributes the field definition emits, nothing lost or added in the encoding
  @Test
  void attributesMatchTheFieldDefinition() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.POST, 502)
            .withClientAddress("2001:db8::1")
            .withTls("TLSv1.3")
            .withError(new IllegalStateException("gone"));
    LogRecord record = LogRecord.parseFrom(OtlpRequestLogSink.encode(info));
    RequestLogFieldsWriterTest.RecordingSink expected = RequestLogFieldsWriterTest.fields(info);
    assertThat(record.getAttributesList())
        .extracting(KeyValue::getKey)
        .containsExactlyElementsOf(expected.fields.keySet());
    assertThat(attributes(record.getAttributesList()).get("client.address").getStringValue())
        .isEqualTo("2001:db8:0:0:0:0:0:1");
    assertThat(record.getSeverityText()).isEqualTo("WARN");
    assertThat(record.getSeverityNumberValue()).isEqualTo(13);
  }

  /// Records of a thread are buffered and sent together
  @Test
  void recordsOfAThreadAreSentTogether() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(60_000).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 25; i++) {
      logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    }
    assertThat(destination.queueSize()).isEqualTo(25);
    destination.close();
    assertThat(received.take().records()).isEqualTo(25);
    assertThat(received).isEmpty();
  }

  /// A full batch is sent before the flush interval is over
  @Test
  void fullBatchIsSentEarly() throws Exception {
    destination =
        new OtlpLogDestination(
            "otlp",
            config().flushIntervalMillis(60_000).batchBytes(OtlpConfig.MIN_BATCH_BYTES).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    int perBatch = (OtlpConfig.MIN_BATCH_BYTES + framedSize(info) - 1) / framedSize(info);
    for (int i = 0; i < perBatch; i++) {
      logger.logRequest(info);
    }
    Received post = received.poll(10, TimeUnit.SECONDS);
    assertThat(post).as("sent without waiting for the interval").isNotNull();
    assertThat(post.records()).isEqualTo(perBatch);
  }

  /// The log server is unavailable for a while: first it answers 503, then it is down, then it is
  /// back. Every record arrives exactly once and in order, nothing is dropped.
  @Test
  void outageLosesNothing() throws Exception {
    status = 503;
    destination = fastDestination(config().batchBytes(OtlpConfig.MIN_BATCH_BYTES).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 1_000;
    for (int i = 0; i < total / 2; i++) {
      logger.logRequest(request("/" + i));
    }
    // a few retries answered with 503
    while (received.size() < 3) {
      Thread.sleep(5);
    }
    server.stop(0);
    for (int i = total / 2; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    // retries against the stopped server
    long retries = counter("sensepitch_request_log_export_retries");
    while (counter("sensepitch_request_log_export_retries") < retries + 3) {
      Thread.sleep(5);
    }
    assertThat(bufferedBytesGauge()).as("backlog waits").isPositive();
    status = 200;
    restartServer();
    while (deliveredPaths().size() < total) {
      Thread.sleep(5);
    }
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      expected.add("/" + i);
    }
    assertThat(deliveredPaths()).as("all, once, in order").isEqualTo(expected);
    assertThat(destination.droppedCount()).isZero();
    assertThat(counter("sensepitch_request_log_export_failed")).isZero();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("Buffering records and retrying"))
        .anySatisfy(m -> assertThat(m).contains("recovered after"));
    await(() -> bufferedBytesGauge() == 0);
  }

  /// A server asking for a delay via `Retry-After` is not asked again meanwhile, although the
  /// backoff of this destination is only milliseconds. The exact delay is tested with
  /// [OtlpLogDestination#retryDelayMillis], without waiting. At shutdown the batch gets a last
  /// attempt, regardless of the requested delay, before its records would be dropped.
  @Test
  void retryAfterIsHonoured() throws Exception {
    answers.add(new Answer(429, "30"));
    destination = fastDestination(config().build());
    new OtlpRequestLogger(destination).logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(received.take().status()).isEqualTo(429);
    assertThat(received.poll(100, TimeUnit.MILLISECONDS)).as("no retry before the delay").isNull();
    assertThat(counter("sensepitch_request_log_export_retries")).isOne();
    destination.close();
    assertThat(received.take().status()).as("last attempt at shutdown").isEqualTo(200);
    assertThat(destination.droppedCount()).isZero();
  }

  /// Exponential backoff with jitter, capped, a longer requested delay wins
  @Test
  void retryDelay() {
    // without jitter (random 0) the delay is half the backoff, with the maximum jitter all of it
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 0, 0, 0)).isEqualTo(500);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 0, 0, 0.999)).isEqualTo(1_000);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 3, 0, 0)).isEqualTo(4_000);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 10, 0, 0))
        .as("capped")
        .isEqualTo(15_000);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 100, 0, 0.999))
        .as("no overflow for many retries, at most the maximum")
        .isBetween(29_900L, 30_000L);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 0, 120_000, 0))
        .as("longer Retry-After wins")
        .isEqualTo(120_000);
    assertThat(OtlpLogDestination.retryDelayMillis(1_000, 30_000, 3, 1_000, 0))
        .as("shorter Retry-After does not shorten the backoff")
        .isEqualTo(4_000);
    assertThat(OtlpLogDestination.retryDelayMillis(1, 1, 0, 0, 0)).as("at least 1 ms").isOne();
  }

  @Test
  void retryAfterParsing() {
    assertThat(OtlpLogDestination.parseRetryAfter(" 2 ")).isEqualTo(2_000);
    assertThat(OtlpLogDestination.parseRetryAfter("-5")).isZero();
    assertThat(OtlpLogDestination.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isZero();
    assertThat(OtlpLogDestination.parseRetryAfter("999999"))
        .isEqualTo(OtlpLogDestination.MAX_RETRY_AFTER_MILLIS);
  }

  /// A status that retrying cannot fix gives up the batch, it is not sent again
  @Test
  void badRequestGivesUpTheBatch() throws Exception {
    answers.add(new Answer(400, null));
    destination = fastDestination(config().flushIntervalMillis(60_000).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    destination.close();
    assertThat(received.take().status()).isEqualTo(400);
    assertThat(received).as("not retried").isEmpty();
    assertThat(counter("sensepitch_request_log_export_failed")).isOne();
    assertThat(counter("sensepitch_request_log_export_retries")).isZero();
    assertThat(destination.droppedCount()).isEqualTo(2);
    assertThat(logs.messages(OtlpLogDestination.class))
        .singleElement()
        .asString()
        .contains("failed, dropped a batch: HTTP status 400");
  }

  /// Given up batches do not stop the export, later batches are sent
  @Test
  void exportContinuesAfterGivingUp() throws Exception {
    answers.add(new Answer(400, null));
    destination = fastDestination(config().build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/lost"));
    assertThat(received.take().status()).isEqualTo(400);
    logger.logRequest(request("/sent"));
    Received next = received.take();
    assertThat(next.status()).isEqualTo(200);
    assertThat(next.paths()).containsExactly("/sent");
  }

  /// When the log server stays down, the waiting batches are limited. The newest records are
  /// dropped, the oldest are kept and sent when the server is back.
  @Test
  void limitDropsTheNewestRecords() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    destination =
        fastDestination(config().batchBytes(batchBytes).maxBufferedBytes(2 * batchBytes).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 10 * batchBytes / framedSize(request("/0000"));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request(String.format("/%04d", i)));
    }
    assertThat(destination.droppedCount()).isPositive();
    assertThat(bufferedBytesGauge()).isLessThanOrEqualTo(2L * batchBytes);
    status = 200;
    destination.close();
    List<String> delivered = deliveredPaths();
    assertThat(delivered.size() + destination.droppedCount()).isEqualTo(total);
    assertThat(delivered).as("the oldest are kept").startsWith("/0000", "/0001").isSorted();
  }

  /// Batches taken during an outage are released again after it, only a few stay in the pool
  @Test
  void poolShrinksAfterAnOutage() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    destination = fastDestination(config().batchBytes(batchBytes).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 20 * batchBytes / framedSize(request("/0"));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    assertThat(bufferedBytesGauge()).isGreaterThan(10L * batchBytes);
    status = 200;
    await(() -> deliveredPaths().size() == total);
    await(() -> bufferedBytesGauge() == 0);
    assertThat(destination.pooledBatches()).as("one thread plus a spare").isLessThanOrEqualTo(2);
  }

  /// Close gives up on an unavailable log server after its timeout and counts what is left
  @Test
  void closeDuringAnOutage() throws Exception {
    server.stop(0);
    // a short close timeout, close waits it out while the log server is unavailable
    destination =
        new OtlpLogDestination(
            "otlp",
            config().flushIntervalMillis(60_000).build(),
            new RequestLogMetrics(),
            10,
            50,
            200);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 10; i++) {
      logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    }
    long start = System.nanoTime();
    destination.close();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000);
    assertThat(destination.droppedCount()).isEqualTo(10);
    assertThat(counter("sensepitch_request_log_export_retries")).isPositive();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("unavailable at shutdown, dropped 10"));
  }

  @Test
  void closeSendsPendingRecords() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(60_000).build());
    new OtlpRequestLogger(destination).logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    destination.close();
    assertThat(received.poll()).isNotNull();
  }

  /// Several threads log at the same time, every record arrives exactly once and intact. While
  /// the sender is busy, the pool provides more batches, so a burst is not dropped.
  @Test
  void concurrentThreads() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(5).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int threads = 4;
    int perThread = 2_000;
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
      worker.join();
    }
    destination.close();
    List<String> paths = deliveredPaths();
    Set<String> unique = new HashSet<>(paths);
    assertThat(destination.droppedCount()).isZero();
    assertThat(paths).hasSize(threads * perThread);
    assertThat(unique).as("no record twice").hasSize(threads * perThread);
  }

  /// A thread that terminated is forgotten, after its last records were sent
  @Test
  void terminatedThreadIsRetired() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(5).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    Thread worker = new Thread(() -> logger.logRequest(request("/last")));
    worker.start();
    worker.join();
    while (deliveredPaths().isEmpty()) {
      Thread.sleep(5);
    }
    assertThat(deliveredPaths()).containsExactly("/last");
  }

  @Test
  void recordAfterCloseIsDropped() {
    destination = new OtlpLogDestination("otlp", config().build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    logger.close();
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(destination.droppedCount()).isOne();
  }

  /// A failing mapping leaves no partial record in the batch
  @Test
  void failedEncodingLeavesNoPartialRecord() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(60_000).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // fails at the last field, when most of the record is already written
    RequestLogInfo broken =
        new AnonymizedClientRequestLogInfo(TestRequestLogInfo.of(HttpMethod.GET, 200)) {
          @Override
          public long totalDurationNanos() {
            throw new IllegalStateException("broken");
          }
        };
    assertThatThrownBy(() -> logger.logRequest(broken)).isInstanceOf(IllegalStateException.class);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    destination.close();
    assertThat(received.take().records()).isOne();
  }

  @Test
  void configValidation() {
    assertThatThrownBy(() -> new OtlpLogDestination("otlp", config().endpoint(null).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint missing");
    assertThatThrownBy(
            () -> new OtlpLogDestination("otlp", config().endpoint("ftp://example.com").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http or https");
    assertThatThrownBy(() -> new OtlpLogDestination("otlp", config().batchBytes(1000).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batchBytes must be at least");
    assertThatThrownBy(
            () ->
                new OtlpLogDestination(
                    "otlp",
                    config()
                        .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
                        .maxBufferedBytes(OtlpConfig.MIN_BATCH_BYTES - 1)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBufferedBytes must be at least batchBytes");
  }

  /// The resource identifies the instance by default, configured attributes override defaults
  @Test
  void resourceDefaultsAndOverrides() throws Exception {
    Map<String, AnyValue> defaults =
        attributes(
            io.opentelemetry.proto.resource.v1.Resource.parseFrom(
                    OtlpLogDestination.encodeResource(null))
                .getAttributesList());
    assertThat(defaults.get("service.name").getStringValue()).isEqualTo("sensepitch-edge");
    assertThat(defaults.get("service.instance.id").getStringValue())
        .isEqualTo(OtlpLogDestination.instanceId())
        .isNotBlank();
    Map<String, AnyValue> configured =
        attributes(
            io.opentelemetry.proto.resource.v1.Resource.parseFrom(
                    OtlpLogDestination.encodeResource(
                        Map.of("service.name", "edge-eu", "service.instance.id", "edge-1")))
                .getAttributesList());
    assertThat(configured.get("service.name").getStringValue()).isEqualTo("edge-eu");
    assertThat(configured.get("service.instance.id").getStringValue()).isEqualTo("edge-1");
  }
}
