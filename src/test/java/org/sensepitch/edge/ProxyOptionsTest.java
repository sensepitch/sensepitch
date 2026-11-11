package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// Proxy settings that are not used by the other tests, which run with the defaults
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class ProxyOptionsTest {

  @Test
  void plainTextRequestLog() {
    var steps =
        new CompleteTest.Steps()
            .given_initialized_proxy_with(
                COMMON_CONFIG.toBuilder()
                    .requestLog(RequestLogConfig.builder().plainText(true).build())
                    .build());
    steps.when_requesting("example.com", "/").then_the_response_status_is(HttpResponseStatus.OK);
    steps.finish_and_check_for_leaks();
    assertThat(steps.requestLog.lines())
        .singleElement()
        .asString()
        .startsWith("RQ0 ")
        .contains("GET / HTTP/1.1 200");
  }

  /// The proxy sends each request to every configured destination. Anonymization per destination
  /// is tested in [RequestLoggerFactoryTest], the embedded test channel has no client address.
  @Test
  void stdoutAndOtlpDestinations() throws Exception {
    BlockingQueue<ExportLogsServiceRequest> posts = new LinkedBlockingQueue<>();
    HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    collector.createContext(
        "/v1/logs",
        exchange -> {
          posts.add(ExportLogsServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    collector.start();
    try {
      RequestLogConfig requestLog =
          RequestLogConfig.builder()
              .destinations(
                  List.of(
                      RequestLogDestinationConfig.builder().type("stdout").build(),
                      RequestLogDestinationConfig.builder()
                          .type("otlp")
                          .name("victorialogs")
                          .anonymizeClientIp(true)
                          .otlp(
                              OtlpConfig.builder()
                                  .endpoint(
                                      "http://127.0.0.1:"
                                          + collector.getAddress().getPort()
                                          + "/v1/logs")
                                  .flushIntervalMillis(1)
                                  .build())
                          .build()))
              .build();
      var steps =
          new CompleteTest.Steps()
              .given_initialized_proxy_with(
                  COMMON_CONFIG.toBuilder().requestLog(requestLog).build());
      steps.when_requesting("example.com", "/").then_the_response_status_is(HttpResponseStatus.OK);
      steps.finish_and_check_for_leaks();
      assertThat(steps.requestLog.lines())
          .singleElement()
          .asString()
          .contains("\"event.name\":\"sensepitch.request\"");
      ExportLogsServiceRequest post = posts.poll(10, TimeUnit.SECONDS);
      assertThat(post).isNotNull();
      LogRecord record = post.getResourceLogs(0).getScopeLogs(0).getLogRecords(0);
      assertThat(record.getAttributesList())
          .extracting(KeyValue::getKey)
          .contains("http.request.method", "url.path");
      String spanId =
          steps.requestLog.lines().getFirst().replaceAll(".*\"span_id\":\"([0-9a-f]+)\".*", "$1");
      assertThat(java.util.HexFormat.of().formatHex(record.getSpanId().toByteArray()))
          .as("both destinations report the same request")
          .isEqualTo(spanId);
      steps.proxy.shutdown();
    } finally {
      collector.stop(0);
    }
  }

  /// Timeouts from the configuration are announced to the client in the keep-alive header.
  @Test
  void connectionSettings() {
    ConnectionConfig connection =
        ConnectionConfig.DEFAULT.toBuilder().readTimeoutSeconds(7).build();
    var steps =
        new CompleteTest.Steps()
            .given_initialized_proxy_with(
                COMMON_CONFIG.toBuilder()
                    .listen(COMMON_CONFIG.listen().toBuilder().connection(connection).build())
                    .build());
    steps
        .when_requesting("example.com", "/")
        .then_the_response_header_is("keep-alive", "timeout=7, max=123");
    steps.finish_and_check_for_leaks();
  }

  /// With the export enabled the metrics server starts with the proxy and stops with it.
  @Test
  void metricsExportStartsAndStops() {
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .metrics(
                MetricsConfig.builder()
                    .enable(true)
                    .prometheus(PrometheusConfig.builder().port(0).build())
                    .build())
            .build();
    Proxy proxy = new Proxy(config, new RequestLogCapture());
    assertThat(proxy.metricsRegistry().scrape().stream().map(s -> s.getMetadata().getName()))
        .contains("sensepitch_ingress_requests_started");
    proxy.shutdown();
  }

  /// The production constructor writes the request log to standard out.
  @Test
  void standardOutConstructor() {
    Proxy proxy = new Proxy(COMMON_CONFIG);
    assertThat(proxy.metricsRegistry().scrape().stream().map(s -> s.getMetadata().getName()))
        .contains("sensepitch_request_log_dropped");
    proxy.shutdown();
  }
}
