package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class MetricsServerTest {

  final HttpClient client = HttpClient.newHttpClient();

  HttpResponse<String> get(int port) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exportsRegistryAndStopsOnClose() throws Exception {
    PrometheusRegistry registry = new PrometheusRegistry();
    Counter.builder().name("test_requests").register(registry).inc(3);
    PrometheusConfig cfg = PrometheusConfig.builder().port(0).enableJvmMetrics(true).build();
    int port;
    try (MetricsServer server = MetricsServer.start(cfg, registry)) {
      port = server.port();
      assertThat(port).isPositive();
      HttpResponse<String> response = get(port);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("test_requests_total 3.0").contains("jvm_memory_used");
    }
    assertThatThrownBy(() -> get(port)).isInstanceOf(ConnectException.class);
  }

  @Test
  void jvmMetricsAreOptional() throws Exception {
    PrometheusRegistry registry = new PrometheusRegistry();
    PrometheusConfig cfg = PrometheusConfig.builder().port(0).build();
    try (MetricsServer server = MetricsServer.start(cfg, registry)) {
      assertThat(get(server.port()).body()).doesNotContain("jvm_memory_used");
    }
  }
}
