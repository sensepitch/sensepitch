package org.sensepitch.edge;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;

/// Exports the metrics of a registry via HTTP in the Prometheus format, at `/metrics`.
///
/// @author Jens Wilke
public class MetricsServer implements AutoCloseable {

  private static final ProxyLogger LOG = ProxyLogger.get(MetricsServer.class);

  private final HTTPServer server;

  private MetricsServer(HTTPServer server) {
    this.server = server;
  }

  /// Starts the HTTP server and adds the JVM metrics to the registry, if enabled.
  ///
  /// @param cfg port `0` binds to an ephemeral port, see [#port()]
  public static MetricsServer start(PrometheusConfig cfg, PrometheusRegistry registry) {
    if (cfg.enableJvmMetrics()) {
      JvmMetrics.builder().register(registry);
    }
    try {
      HTTPServer server = HTTPServer.builder().port(cfg.port()).registry(registry).buildAndStart();
      LOG.info(
          "Prometheus HTTP server listening on port http://localhost:"
              + server.getPort()
              + "/metrics");
      return new MetricsServer(server);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The port the server is bound to
  public int port() {
    return server.getPort();
  }

  /// Stops the HTTP server
  @Override
  public void close() {
    server.close();
  }
}
