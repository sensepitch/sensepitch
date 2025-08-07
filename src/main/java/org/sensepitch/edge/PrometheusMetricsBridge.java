package org.sensepitch.edge;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
public class PrometheusMetricsBridge implements MetricsBridge {

  static ProxyLogger DEBUG = ProxyLogger.get(PrometheusMetricsBridge.class);

  private final PrometheusRegistry prometheusRegistry;

  public PrometheusMetricsBridge(PrometheusConfig cfg) {
    prometheusRegistry = PrometheusRegistry.defaultRegistry;
    if (cfg.enableJvmMetrics()) {
      JvmMetrics.builder().register();
    }
    HTTPServer server = null;
    try {
      server = HTTPServer.builder().port(cfg.port()).registry(prometheusRegistry).buildAndStart();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    DEBUG.info(
        "Prometheus HTTP server listening on port http://localhost:"
            + server.getPort()
            + "/metrics");
  }

  @Override
  public <T extends HasMetrics> T expose(T objectWithMetrics) {
    String prefix = camelToSnake(objectWithMetrics.getClass().getSimpleName()) + "_";
    Object target = objectWithMetrics.getMetrics();
    // find all zero-arg getters that return long or Long
    for (Method m : target.getClass().getMethods()) {
      if (m.getParameterCount() == 0
          && (m.getReturnType() == long.class || m.getReturnType() == Long.class)) {
        Collector c = createCounterCollector(prefix, target, m);
        prometheusRegistry.register(c);
      }
    }
    return objectWithMetrics;
  }

  @Override
  public <T extends HasMultipleMetrics> T expose(T objectWithMetrics) {
    Consumer<Collector> consumer = prometheusRegistry::register;
    objectWithMetrics.registerCollectors(consumer);
    return objectWithMetrics;
  }

  @Override
  public <T extends Collector> T expose(T objectWithPrometheusMetrics) {
    prometheusRegistry.register(objectWithPrometheusMetrics);
    return objectWithPrometheusMetrics;
  }

  Collector createCounterCollector(String prefix, Object target, Method method) {
    String metricName = prefix + methodNameToMetricName(method.getName());
    return new Collector() {
      @Override
      public MetricSnapshot collect() {
        long value = 0;
        try {
          Object result = method.invoke(target);
          value = result == null ? 0L : ((Number) result).longValue();
        } catch (Exception e) {
          // ignore
        }
        return CounterSnapshot.builder()
            .name(metricName)
            .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().value(value).build())
            .build();
      }

      @Override
      public String getPrometheusName() {
        return metricName;
      }
    };
  }

  private static String camelToSnake(String str) {
    // insert underscores before capitals, lower-case everything
    StringBuilder sb = new StringBuilder(str.length());
    for (char c : str.toCharArray()) {
      if (Character.isUpperCase(c)) {
        if (sb.length() > 0) sb.append('_');
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String methodNameToMetricName(String str) {
    if (str.startsWith("get")) {
      str = str.substring(3);
    }
    if (str.endsWith("Count")) {
      str = str.substring(0, str.length() - 5);
    }
    return camelToSnake(str);
  }
}
