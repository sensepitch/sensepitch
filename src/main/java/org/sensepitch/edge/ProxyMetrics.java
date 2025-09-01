package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;

import java.util.concurrent.atomic.LongAdder;
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

  public final LongAdder ingressRequestsStarted = new LongAdder();
  public final LongAdder ingressRequestsCompleted = new LongAdder();
  public final LongAdder ingressRequestsAborted = new LongAdder();

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    metricSet.forEach(consumer);
    // TODO: add 1 per ingress, so we can drill down per ingress and know how many servers it has
    consumer.accept(
      () ->
        GaugeSnapshot.builder()
          .name("sensepitch_edge_server_live")
          .help("Always 1, might be 0 in the future when the server is not accepting connections, e.g. warming up")
          .dataPoint(
            GaugeSnapshot.GaugeDataPointSnapshot.builder()
              .value(1)
              .labels(Labels.EMPTY)
              .build())
          .build());
    consumer.accept(() ->
      CounterSnapshot.builder()
        .name("sensepitch_ingress_requests_started")
        .help("Increments as soon as an HTTP header from a client is received")
        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
          .value(ingressRequestsStarted.sum())
          .build())
        .build()
    );
    consumer.accept(() ->
      CounterSnapshot.builder()
        .name("sensepitch_ingress_requests_completed")
        .help("Increments once the response was received by the client")
        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
          .value(ingressRequestsCompleted.sum())
          .build())
        .build()
    );
    consumer.accept(() ->
      CounterSnapshot.builder()
        .name("sensepitch_ingress_requests_aborted")
        .help("The response could not be sent to the client, e.g. when the connection is reset")
        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
          .value(ingressRequestsAborted.sum())
          .build())
        .build()
    );
    consumer.accept(() ->
      GaugeSnapshot.builder()
        .name("sensepitch_ingress_requests_in_flight")
        .help("Number of requests currently in flight")
        .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
          .value(
            ingressRequestsStarted.sum() - ingressRequestsCompleted.sum() - ingressRequestsAborted.sum())
          .build())
        .build()
    );
  }
}
