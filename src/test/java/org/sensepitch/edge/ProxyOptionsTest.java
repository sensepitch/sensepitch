package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;

import io.netty.handler.codec.http.HttpResponseStatus;
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
