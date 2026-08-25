package org.sensepitch.edge;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.Unit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/// Exposes netty's own accounting of the memory used by its byte buffers.
///
/// Expose consistent metrics for Netties buffers, aka, memory pools. Netty 4.2 allocates the chunks
/// of the pooling allocators from a shared `java.lang.foreign.Arena`, which is intentionally
/// outside the accounting of `java.nio`, so `jvm_buffer_pool_used_bytes{pool="direct"}` does not
/// see them.
///
/// Both the accepted connections and the upstream connections use `ByteBufAllocator.DEFAULT`,
/// reported as `default`, which is netty's `AdaptiveByteBufAllocator` and is documented as
/// experimental, so it is the series to watch. `pooled` should stay at zero, which is the invariant
/// that nobody has pinned it again, see [Proxy] and [DefaultUpstream]. `unpooled` is the static
/// content cached by [ResourceFiles], a fixed baseline of about 16 KB.
///
/// Only `Unpooled.buffer()` and `Unpooled.directBuffer()` are accounted for here. The
/// `Unpooled.copiedBuffer` and `wrappedBuffer` helpers construct their buffer directly and bypass
/// the allocator, so those call sites are invisible in this metric and show up in the heap metrics
/// instead.
///
/// These numbers are netty's logical usage, decremented on `release()`, so they say how much of the
/// pooled memory is handed out, not how much the process holds. For the latter, run with
/// `-XX:NativeMemoryTracking=summary`, see the README, and the prometheus client exposes it as
/// `jvm_native_memory_committed_bytes{pool="Other"}`.
///
/// @author Jens Wilke
public class NettyAllocatorMetrics implements HasMultipleMetrics {

  static final String METRIC_NAME = "sensepitch_netty_allocator_used_memory_bytes";

  private final Map<String, ByteBufAllocatorMetric> allocators = new LinkedHashMap<>();

  public NettyAllocatorMetrics() {
    add("pooled", PooledByteBufAllocator.DEFAULT);
    add("unpooled", UnpooledByteBufAllocator.DEFAULT);
    // usually a distinct AdaptiveByteBufAllocator, but can be configured to one of the above
    if (ByteBufAllocator.DEFAULT != PooledByteBufAllocator.DEFAULT
        && ByteBufAllocator.DEFAULT != UnpooledByteBufAllocator.DEFAULT) {
      add("default", ByteBufAllocator.DEFAULT);
    }
  }

  private void add(String name, ByteBufAllocator allocator) {
    if (allocator instanceof ByteBufAllocatorMetricProvider provider) {
      allocators.put(name, provider.metric());
    }
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(
        () -> {
          GaugeSnapshot.Builder builder =
              GaugeSnapshot.builder()
                  .name(METRIC_NAME)
                  .help("Memory used by the netty byte buffer allocators")
                  .unit(Unit.BYTES);
          allocators.forEach(
              (name, metric) -> {
                builder.dataPoint(dataPoint(name, "direct", metric.usedDirectMemory()));
                builder.dataPoint(dataPoint(name, "heap", metric.usedHeapMemory()));
              });
          return builder.build();
        });
  }

  private static GaugeSnapshot.GaugeDataPointSnapshot dataPoint(
      String allocator, String type, long value) {
    return GaugeSnapshot.GaugeDataPointSnapshot.builder()
        .value(value)
        .labels(Labels.of("allocator", allocator, "type", type))
        .build();
  }
}
