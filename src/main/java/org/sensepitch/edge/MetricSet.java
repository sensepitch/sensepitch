package org.sensepitch.edge;

import io.prometheus.metrics.core.metrics.Metric;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Jens Wilke
 */
public class MetricSet implements Iterable<Metric> {

  private final Set<Metric> metrics = new HashSet<>();

  public <T extends Metric> T add(T metric) {
    metrics.add(metric);
    return metric;
  }

  @Override
  public Iterator<Metric> iterator() {
    return metrics.iterator();
  }
}
