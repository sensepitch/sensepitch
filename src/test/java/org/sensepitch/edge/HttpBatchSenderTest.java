package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// The posting does not depend on the payload format: a format of plain text lines, one per
/// record, goes through the same [HttpBatchSender] and [BatchingRequestLogger] as OTLP.
///
/// @author Jens Wilke
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HttpBatchSenderTest {

  /// Records as text lines
  static final class TextBatch extends Batch {

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    int size() {
      return bytes.size();
    }

    @Override
    void clear() {
      bytes.reset();
    }
  }

  /// One line with the path per record, the body is the lines of all batches
  static final class TextPayload implements BatchPayload<TextBatch> {

    @Override
    public TextBatch newBatch(int batchBytes) {
      return new TextBatch();
    }

    @Override
    public RecordEncoder<TextBatch> newEncoder() {
      return (batch, info) ->
          batch.bytes.writeBytes((info.request().uri() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeBody(List<TextBatch> batches, BodySink sink) {
      for (TextBatch batch : batches) {
        byte[] bytes = batch.bytes.toByteArray();
        sink.write(bytes, 0, bytes.length);
      }
    }

    @Override
    public String contentType() {
      return "text/plain";
    }
  }

  /// Sender of text lines, with a short backoff
  static HttpBatchSender<TextBatch> textSender(String endpoint, Map<String, String> headers) {
    return textSender(endpoint, headers, OtlpConfig.MIN_BATCH_BYTES, 64L * 1024 * 1024);
  }

  static HttpBatchSender<TextBatch> textSender(
      String endpoint, Map<String, String> headers, int batchBytes, long maxBufferedBytes) {
    return new HttpBatchSender<>(
        new HttpBatchSender.Settings(
            "text",
            URI.create(endpoint),
            headers,
            Duration.ofSeconds(5),
            batchBytes,
            maxBufferedBytes,
            2,
            60_000,
            60_000,
            false,
            new Backoff(10, 50)),
        new TextPayload(),
        new RequestLogMetrics());
  }

  /// Ends the batcher of the calling thread, finishes the sender and waits until it stopped, the
  /// way a shutdown does it for an event loop
  static void close(HttpBatchSender<?> sender) throws Exception {
    sender.endThread();
    sender.finish().get(10, TimeUnit.SECONDS);
  }

  /// No log server listens on port 1, connecting fails at once
  static final String UNREACHABLE = "http://127.0.0.1:1/lines";

  static TestRequestLogInfo request(String path) {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri(path);
    return info;
  }

  @Test
  void statusOutcomes() {
    assertThat(HttpBatchSender.result(199, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    assertThat(HttpBatchSender.result(200, 0)).isSameAs(HttpBatchSender.Result.SENT);
    assertThat(HttpBatchSender.result(299, 0)).isSameAs(HttpBatchSender.Result.SENT);
    assertThat(HttpBatchSender.result(300, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    assertThat(HttpBatchSender.result(400, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    for (int status : new int[] {429, 502, 503, 504}) {
      HttpBatchSender.Result result = HttpBatchSender.result(status, 7);
      assertThat(result.outcome()).as("status " + status).isEqualTo(HttpBatchSender.Outcome.RETRY);
      assertThat(result.retryAfterMillis()).isEqualTo(7);
      assertThat(result.reason()).isEqualTo("HTTP status " + status);
      assertThat(result.error()).isEqualTo("http_" + status);
    }
    assertThat(HttpBatchSender.result(400, 0).error()).isEqualTo("http_400");
  }

  /// The short error codes of the metrics, see [CircuitBreakerMetrics]
  @Test
  void errorCodes() {
    assertThat(HttpBatchSender.failure(new java.net.ConnectException("refused")).error())
        .isEqualTo("connect");
    assertThat(
            HttpBatchSender.failure(new java.net.http.HttpConnectTimeoutException("slow")).error())
        .isEqualTo("connect");
    assertThat(HttpBatchSender.failure(new java.net.http.HttpTimeoutException("slow")).error())
        .isEqualTo("timeout");
    assertThat(HttpBatchSender.failure(new java.io.IOException("reset")).error()).isEqualTo("io");
    assertThat(HttpBatchSender.failure(new IllegalStateException("bug")).error())
        .isEqualTo("unexpected");
  }

  @Test
  void failureOutcomes() {
    java.io.IOException refused = new java.net.ConnectException("refused");
    assertThat(HttpBatchSender.failure(refused).outcome()).isEqualTo(HttpBatchSender.Outcome.RETRY);
    assertThat(HttpBatchSender.failure(new java.util.concurrent.CompletionException(refused)))
        .as("unwrapped")
        .extracting(HttpBatchSender.Result::outcome, HttpBatchSender.Result::reason)
        .containsExactly(HttpBatchSender.Outcome.RETRY, refused.toString());
    assertThat(HttpBatchSender.failure(new IllegalStateException("bug")))
        .extracting(HttpBatchSender.Result::outcome, HttpBatchSender.Result::reason)
        .containsExactly(
            HttpBatchSender.Outcome.GIVE_UP, "unexpected java.lang.IllegalStateException: bug");
  }

  /// A header the HTTP client refuses is a configuration error, reported at construction
  @Test
  void invalidHeaderIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> textSender(UNREACHABLE, Map.of("Content-Length", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Request log destination text: invalid header Content-Length");
  }

  /// A thread that sets up its batcher after the sender finished gets a closed one, which refuses
  /// records and takes no batch
  @Test
  void batcherCreatedAfterFinishIsClosed() {
    HttpBatchSender<TextBatch> sender = textSender(UNREACHABLE, Map.of());
    sender.finish();
    ThreadBatcher<TextBatch> batcher = sender.newBatcher();
    assertThat(batcher.log(request("/late"))).isFalse();
    assertThat(batcher.records()).isZero();
    assertThat(sender.pooledBatches()).isZero();
  }

  /// The flush timer takes a partly filled batch only if it has records and the waiting batches
  /// have room for it. Each line is 3 bytes, a batch of 4 bytes is full with the second.
  @Test
  void partialBatchNeedsRecordsAndRoom() throws Exception {
    HttpBatchSender<TextBatch> roomy = textSender(UNREACHABLE, Map.of(), 4, 1_000);
    ThreadBatcher<TextBatch> batcher = roomy.newBatcher();
    assertThat(batcher.takePartial()).as("no batch yet").isNull();
    batcher.log(request("/a"));
    assertThat(batcher.takePartial().records).isOne();
    assertThat(batcher.takePartial()).as("taken already").isNull();
    batcher.log(request("/b"));
    batcher.log(request("/c"));
    assertThat(batcher.records()).as("full batch handed off").isZero();
    assertThat(batcher.takePartial()).as("empty batch").isNull();
    // nothing listens, no waiting for the drain
    roomy.endThread();
    roomy.finish();
    HttpBatchSender<TextBatch> full = textSender(UNREACHABLE, Map.of(), 4, 4);
    batcher = full.newBatcher();
    batcher.log(request("/a"));
    batcher.log(request("/b"));
    assertThat(batcher.records()).as("hand-off refused, the full batch is kept").isEqualTo(2);
    assertThat(batcher.takePartial()).as("no room").isNull();
    // the kept full batch can never be sent, the limit holds it back, so no waiting
    full.endThread();
    full.finish();
  }

  /// The sender stops once it is finishing, all its batchers are closed and all is sent. A
  /// batcher may close before or after the sender finishes, and more than once.
  @Test
  void stopsAfterTheEndMarkersOfAllThreads() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    HttpServer server = lineServer(bodies);
    try {
      HttpBatchSender<TextBatch> sender = textSender(endpoint(server), Map.of());
      assertThat(sender.finish()).as("no batchers, stops at once").isDone();
      HttpBatchSender<TextBatch> busy = textSender(endpoint(server), Map.of());
      ThreadBatcher<TextBatch> early = busy.newBatcher();
      ThreadBatcher<TextBatch> late = busy.newBatcher();
      early.log(request("/early"));
      late.log(request("/late"));
      early.close();
      CompletableFuture<Void> stopped = busy.finish();
      assertThat(bodies.poll(10, TimeUnit.SECONDS)).isEqualTo("/early\n");
      assertThat(stopped).as("one batcher still open").isNotDone();
      assertThat(late.log(request("/accepted"))).as("open batchers still log").isTrue();
      late.close();
      late.close();
      stopped.get(10, TimeUnit.SECONDS);
      assertThat(bodies.poll(10, TimeUnit.SECONDS)).isEqualTo("/late\n/accepted\n");
      assertThat(busy.droppedCount()).isZero();
    } finally {
      server.stop(0);
    }
  }

  static HttpServer lineServer(BlockingQueue<String> bodies) throws java.io.IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lines",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  static String endpoint(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/lines";
  }

  @Test
  void textLinesArePosted() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    BlockingQueue<String> contentTypes = new LinkedBlockingQueue<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lines",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      HttpBatchSender<TextBatch> sender =
          textSender("http://127.0.0.1:" + server.getAddress().getPort() + "/lines", Map.of());
      BatchingRequestLogger<TextBatch> logger = new BatchingRequestLogger<>(sender);
      for (String path : List.of("/a", "/b", "/c")) {
        logger.logRequest(request(path));
      }
      logger.close();
      sender.stopped().get(10, TimeUnit.SECONDS);
      assertThat(bodies.poll()).isEqualTo("/a\n/b\n/c\n");
      assertThat(contentTypes.poll()).isEqualTo("text/plain");
      assertThat(sender.droppedCount()).isZero();
    } finally {
      server.stop(0);
    }
  }
}
