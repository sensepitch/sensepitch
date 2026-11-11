package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Logging a request via OTLP must not allocate in steady state, see [OtlpThreadLogger].
///
/// Measured with the allocation counter of the JVM for the calling thread, so the work of the
/// sender thread of the destination is not included. What remains are the iterators Netty
/// creates when the request headers are iterated, about 32 bytes each.
///
/// The log server answers, as in steady state. While it is unavailable, batches pile up and new
/// ones are allocated, which is intended, see [OtlpLogDestination]. The best of a few rounds is
/// taken, a round may need a new batch when the sender has not recycled one yet.
///
/// @author Jens Wilke
class OtlpAllocationTest {

  static final int RECORDS = 20_000;

  /// Bound per record. Measured are 120 to 150 bytes, the four header iterators of this request
  /// (three logged headers and the header names), the encoding and the anonymization add nothing.
  /// The bound leaves room for JIT variance. Before the per-thread buffers, logging allocated about
  /// 14 KB per record.
  static final long MAX_BYTES_PER_RECORD = 256;

  static TestRequestLogInfo browserRequest() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200)
            .withClientAddress("192.0.2.7")
            .withTls("TLSv1.3");
    info.request().setUri("/search?q=edge");
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64)");
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "de-DE,de;q=0.9");
    info.request().headers().set(HttpHeaderNames.REFERER, "https://example.org/");
    info.request().headers().set(RequestLogFieldsWriter.SEC_CH_UA_MOBILE, "?0");
    return info;
  }

  static final int ROUNDS = 3;

  static long bytesPerRecord(RequestLogger logger, RequestLogInfo info) {
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    // warm up: thread local setup, pool and JIT compilation
    for (int i = 0; i < RECORDS; i++) {
      logger.logRequest(info);
    }
    long best = Long.MAX_VALUE;
    for (int round = 0; round < ROUNDS; round++) {
      long before = threads.getCurrentThreadAllocatedBytes();
      for (int i = 0; i < RECORDS; i++) {
        logger.logRequest(info);
      }
      best = Math.min(best, (threads.getCurrentThreadAllocatedBytes() - before) / RECORDS);
    }
    return best;
  }

  /// A log server that accepts everything
  static HttpServer logServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/logs",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void loggingDoesNotAllocate(boolean anonymize) throws IOException {
    HttpServer server = logServer();
    OtlpLogDestination destination =
        new OtlpLogDestination(
            "otlp",
            OtlpConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs")
                .flushIntervalMillis(60_000)
                .build());
    RequestLogger logger = new OtlpRequestLogger(destination);
    if (anonymize) {
      logger = new AnonymizingRequestLogger(logger);
    }
    try {
      long bytes = bytesPerRecord(logger, browserRequest());
      assertThat(bytes).as("bytes allocated per record").isLessThanOrEqualTo(MAX_BYTES_PER_RECORD);
    } finally {
      logger.close();
      server.stop(0);
    }
  }

  /// The mapping itself, without encoding, allocates only the header iterators
  @Test
  void mappingDoesNotAllocate() {
    RequestLogSink discard =
        new RequestLogSink() {
          @Override
          public void record(
              long timeMillis,
              long observedTimeMillis,
              RequestLogFieldsWriter.Severity severity,
              String traceId,
              String spanId,
              String traceFlags) {}

          @Override
          public void body(String method, String uri, int status) {}

          @Override
          public void string(String key, CharSequence value) {}

          @Override
          public void string(String key, CharSequence value, int start, int end) {}

          @Override
          public void address(String key, java.net.InetAddress address) {}

          @Override
          public void number(String key, long value) {}

          @Override
          public void bool(String key, boolean value) {}

          @Override
          public void startArray(String key) {}

          @Override
          public void arrayValue(CharSequence value) {}

          @Override
          public void endArray() {}
        };
    RequestLogger mappingOnly = info -> RequestLogFieldsWriter.write(info, discard);
    assertThat(bytesPerRecord(mappingOnly, browserRequest()))
        .isLessThanOrEqualTo(MAX_BYTES_PER_RECORD);
  }
}
