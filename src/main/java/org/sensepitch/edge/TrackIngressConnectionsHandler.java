package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/// Counts the ingress connections and keeps the open ones, so a shutdown can drain them, see
/// [#drain()].
///
/// @author Jens Wilke
@ChannelHandler.Sharable
public class TrackIngressConnectionsHandler extends ChannelInboundHandlerAdapter
    implements HasMultipleMetrics {

  /// Fired into every open ingress connection when the proxy shuts down, see
  /// [IngressTimeoutHandler]
  public enum ShutdownEvent {
    SHUTDOWN
  }

  private final LongAdder connectionOpened = new LongAdder();
  private final LongAdder connectionClosed = new LongAdder();

  /// Open connections, a closed channel is removed by the group
  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

  /// Asks every open connection to close: idle ones at once, the others after their response.
  /// Called after the listener was closed, so no connection is added anymore.
  ///
  /// @return completed when all these connections are closed
  public ChannelGroupFuture drain() {
    ChannelGroupFuture closed = channels.newCloseFuture();
    channels.forEach(channel -> channel.pipeline().fireUserEventTriggered(ShutdownEvent.SHUTDOWN));
    return closed;
  }

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
    channels.add(ctx.channel());
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    connectionClosed.increment();
    super.channelInactive(ctx);
  }
}
