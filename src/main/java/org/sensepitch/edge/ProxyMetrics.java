package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.Collector;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
public class ProxyMetrics implements HasMultipleMetrics {

  private final MetricSet metricSet = new MetricSet();
  public final Counter ingressErrorCounter =
      metricSet.add(Counter.builder().name("ingress_error").labelNames("phase", "type").build());

  public final Counter ingressReceiveTimeoutCounter =
      metricSet.add(Counter.builder().name("ingress_receive_timeout").labelNames("phase").build());

  public final CounterDataPoint ingressReceiveTimeoutFirstRequest =
      ingressReceiveTimeoutCounter.labelValues("first_request");

  public final CounterDataPoint ingressReceiveTimeoutKeepAlive =
      ingressReceiveTimeoutCounter.labelValues("keep_alive");

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    metricSet.forEach(consumer);
  }
}
