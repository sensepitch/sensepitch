package org.sensepitch.edge;

import io.prometheus.metrics.model.registry.Collector;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
public interface HasMultipleMetrics {

  void registerCollectors(Consumer<Collector> consumer);
}
