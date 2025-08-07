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
  public final Counter ingressConnectionErrorCounter =
      metricSet.add(
          Counter.builder().name("ingress_connection_error").labelNames("phase", "type").build());

  public final CounterDataPoint ingressConnectionErrorSslHandshake =
      ingressConnectionErrorCounter.labelValues("handshake", "ssl");
  public final CounterDataPoint ingressConnectionResetDuringHandshake =
      ingressConnectionErrorCounter.labelValues("handshake", "connection_reset");
  public final CounterDataPoint ingressOtherHandshakeError =
      ingressConnectionErrorCounter.labelValues("handshake", "other");
  public final CounterDataPoint ingressConnectionErrorRequestReceiveConnectionReset =
      ingressConnectionErrorCounter.labelValues("request", "connection_reset");
  public final CounterDataPoint ingressConnectionErrorRequestReceiveOther =
      ingressConnectionErrorCounter.labelValues("request", "other");
  public final CounterDataPoint ingressConnectionErrorContentReceiveConnectionReset =
      ingressConnectionErrorCounter.labelValues("content", "connection_reset");
  public final CounterDataPoint ingressConnectionErrorContentReceiveOther =
      ingressConnectionErrorCounter.labelValues("content", "other");
  public final CounterDataPoint ingressConnectionErrorRespondingConnectionReset =
      ingressConnectionErrorCounter.labelValues("response", "connection_reset");
  public final CounterDataPoint ingressConnectionErrorRespondingOther =
      ingressConnectionErrorCounter.labelValues("response", "other");

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
