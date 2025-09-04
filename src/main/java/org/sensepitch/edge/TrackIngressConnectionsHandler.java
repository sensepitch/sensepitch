package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class TrackIngressConnectionsHandler extends ChannelInboundHandlerAdapter
    implements HasMultipleMetrics {

  private final LongAdder connectionOpened = new LongAdder();
  private final LongAdder connectionClosed = new LongAdder();

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(
        () ->
            CounterSnapshot.builder()
                .name("sensepitch_ingress_connections_opened")
                .help("Connections opened")
                .dataPoint(
                    CounterSnapshot.CounterDataPointSnapshot.builder()
                        .value(connectionOpened.sum())
                        .labels(Labels.EMPTY)
                        .build())
                .build());
    consumer.accept(
        () ->
            CounterSnapshot.builder()
                .name("sensepitch_ingress_connections_closed")
                .help("Connections closed")
                .dataPoint(
                    CounterSnapshot.CounterDataPointSnapshot.builder()
                        .value(connectionClosed.sum())
                        .labels(Labels.EMPTY)
                        .build())
                .build());
    consumer.accept(
        () ->
            GaugeSnapshot.builder()
                .name("sensepitch_ingress_connections_active")
                .help("Number of currently active connections (opened - closed)")
                .dataPoint(
                    GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .value(connectionOpened.sum() - connectionClosed.sum())
                        .labels(Labels.EMPTY)
                        .build())
                .build());
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    connectionOpened.increment();
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    connectionClosed.increment();
    super.channelInactive(ctx);
  }
}
