package org.sensepitch.edge;

import io.prometheus.metrics.model.registry.Collector;

/**
 * @author Jens Wilke
 */
public interface MetricsBridge {

  /**
   * If needed exposes the metrics from this object;
   *
   * @return Returns the parameter object, so the method can be "chained" in
   */
  <T extends HasMetrics> T expose(T objectWithMetrics);

  <T extends Collector> T expose(T objectWithMetrics);

  <T extends HasMultipleMetrics> T expose(T objectWithMetrics);

  class NoMetricsExposed implements MetricsBridge {
    @Override
    public <T extends HasMetrics> T expose(T objectWithMetrics) {
      return objectWithMetrics;
    }

    @Override
    public <T extends Collector> T expose(T objectWithMetrics) {
      return objectWithMetrics;
    }

    @Override
    public <T extends HasMultipleMetrics> T expose(T objectWithMetrics) {
      return objectWithMetrics;
    }
  }
}
