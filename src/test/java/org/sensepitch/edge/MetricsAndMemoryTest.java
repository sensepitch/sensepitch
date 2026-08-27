package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// Test our understanding of the JVM memory metrics w.r.t. to the Netty byte buffers.
///
/// The metrics that reflect netty's off heap buffers are
/// `jvm_buffer_pool_used_bytes{pool="direct"}`, `process_resident_memory_bytes` and
/// `process_virtual_memory_bytes`. Which of them actually work depends on how netty allocates the
/// memory, and that differs both between netty versions, see [#jvmMemoryMetricForDirectByteBuffer],
/// and between allocators, see [#pooledAllocatorMemoryIsInvisibleToTheJvmBufferPool].
///
/// @author Jens Wilke
class MetricsAndMemoryTest {

  PrometheusRegistry reg = new PrometheusRegistry();

  @Test
  void exportsCounterWithValue() throws Exception {
    Counter ops =
        Counter.builder()
            .name("my_ops_total")
            .help("Number of ops")
            .labelNames("status")
            .register(reg);
    // exercise code under test
    ops.labelValues("ok").inc(3);
    var snapshots = new Snapshots(reg.scrape());
    var counter = snapshots.counter("my_ops");
    assertThat(counter.getValue()).isEqualTo(3);
  }

  /// The general Java memory metrics `jvm_memory_used_bytes` do not reflect the usage of the byte
  /// buffers that netty is using. The buffers are reflected in
  /// `jvm_buffer_pool_used_bytes{pool="direct"}` and in the total RSS memory consumption.
  ///
  /// This did not hold with netty 4.2.3 on Java 24+, where `sun.misc.Unsafe` memory access is
  /// disabled by default and 4.2.3 fell back to allocating from a shared `java.lang.foreign.Arena`.
  /// That memory is intentionally outside the accounting of `java.nio`: measured over 200 MiB, no
  /// `BufferPoolMXBean` or `MemoryPoolMXBean` moved at all, a sweep of all 152 numeric platform
  /// MBean attributes found nothing that attributes it to a pool, and `-XX:MaxDirectMemorySize=64m`
  /// did not bound it while `ByteBuffer.allocateDirect` failed with `OutOfMemoryError` under the
  /// same limit. Only the OS level counters and the native memory tracking category `Other`
  /// (`12_288` -> `209_727_488`) saw it.
  ///
  /// Newer netty allocates *unpooled* buffers via `ByteBuffer.allocateDirect` again, because
  /// closing a shared arena turned out to be expensive. The arena path still ships,
  /// `-Dio.netty.ignoreExpensiveClean=true` brings it back here and makes this test fail again, and
  /// the pooling allocators still use it, see
  /// [#pooledAllocatorMemoryIsInvisibleToTheJvmBufferPool]. This test allocates from `Unpooled`.
  ///
  /// Reclamation is GC driven now. Freeing an `allocateDirect` buffer eagerly needs
  /// `Unsafe.invokeCleaner`, so without unsafe `release()` hands the buffer back to netty but not
  /// the memory back to the JVM. Measured over a 100 MiB cycle: the allocation adds `104_857_601`,
  /// the release frees `0`, and only after a `System.gc()` does the pool drop. Hence the wait
  /// before the shrink assertions. With `--sun-misc-unsafe-memory-access=allow
  /// -Dio.netty.tryUnsafe=true` the release frees immediately and the test passes just as well.
  @Test
  void jvmMemoryMetricForDirectByteBuffer() throws Exception {
    // jvm_buffer_pool_used_bytes
    // jvm_memory_used_bytes
    // process_resident_memory_bytes
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    // initialize Netty classes for buffers
    ByteBuf dummyBuf = Unpooled.directBuffer(123);
    dummyBuf.release();
    Snapshots s;
    Snapshots s0 = s = snapshots();
    printMetrics(s);
    ByteBuf buf = Unpooled.directBuffer(sampleAllocation);
    Snapshots s1 = s = snapshots();
    printMetrics(s);
    assertThat(s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
        .as("direct buffer usage should grow by at least %s", sampleAllocation)
        .isGreaterThanOrEqualTo(
            s0.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue() + sampleAllocation);
    assertThat(s1.gauge("process_resident_memory_bytes").getValue())
        .as("resident memory grows by at least %s", sampleAllocation / 2)
        .isGreaterThanOrEqualTo(
            s0.gauge("process_resident_memory_bytes").getValue() + sampleAllocation / 2);
    // zeroing the buffer does not have an effect
    // for (int i = 0; i < buf.capacity(); i += 4096) { // touch each 4 KB page
    //   buf.setByte(i, (byte) 0);
    // }
    buf.release();
    awaitDirectPoolBelow(
        s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue() - sampleAllocation);
    Snapshots s2 = s = snapshots();
    printMetrics(s);
    assertThat(s2.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
        .as("direct buffer usage should shrink by at least %s", sampleAllocation)
        .isLessThanOrEqualTo(
            s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue() - sampleAllocation);
    // RSS shrinks also, however, we cannot expect that it shrinks exactly 100M, since other stuff
    // might be allocated meanwhile
    assertThat(s2.gauge("process_resident_memory_bytes").getValue())
        .as("resident memory shrinks by at least %s", sampleAllocation / 2)
        .isLessThanOrEqualTo(
            s1.gauge("process_resident_memory_bytes").getValue() + sampleAllocation / 2);
  }

  /// `process_virtual_memory_bytes` grows with the netty buffers whichever way netty allocates
  /// them, which is what makes it worth a test of its own: it was the only metric besides RSS that
  /// moved while 4.2.3 was allocating from a `java.lang.foreign.Arena`.
  ///
  /// It is a growth signal only. Virtual memory does not shrink reliably, measured over an
  /// allocate, release and `System.gc()` cycle it went `20_041_437_184` -> `20_282_613_760` ->
  /// `20_518_559_744`: the GC that gave the buffer back mapped more address space than the buffer
  /// occupied.
  ///
  /// Which is why it is not the metric to watch for a memory leak, tempting as it looks, since it
  /// does capture every kind of allocation. The baseline drowns the signal: `21_063_483_392` of
  /// virtual against `227_110_912` resident under G1, and `270_950_637_568` against `306_102_272`
  /// under ZGC, which the README uses. That is 93 and 885 times the resident size, so a 100 MiB
  /// leak is a 0.04 percent change. Use `process_resident_memory_bytes` instead, see the README.
  @Test
  void virtualMemoryMetricReflectsDirectByteBuffer() throws Exception {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    // initialize Netty classes for buffers
    ByteBuf dummyBuf = Unpooled.directBuffer(123);
    dummyBuf.release();
    Snapshots s0 = snapshots();
    printMetrics(s0);
    ByteBuf buf = Unpooled.directBuffer(sampleAllocation);
    Snapshots s1 = snapshots();
    printMetrics(s1);
    try {
      assertThat(s1.gauge("process_virtual_memory_bytes").getValue())
          .as("virtual memory grows by at least %s", sampleAllocation)
          .isGreaterThanOrEqualTo(
              s0.gauge("process_virtual_memory_bytes").getValue() + sampleAllocation);
    } finally {
      buf.release();
    }
  }

  /// The pooling allocators do not use the same allocation path as `Unpooled`. Netty 4.2.17
  /// allocates unpooled buffers with `ByteBuffer.allocateDirect`, so those appear in the JVM direct
  /// pool, but the chunks of [PooledByteBufAllocator] and [AdaptiveByteBufAllocator] come from a
  /// shared `java.lang.foreign.Arena`, where the expensive close is amortised over a long lived
  /// chunk. Measured with 100 MiB: unpooled moves the pool by `104_857_600`, pooled and adaptive by
  /// `0`. Native memory tracking does see them, category `Other` grows by `209_715_208` for a live
  /// 200 MiB pooled buffer, and netty bounds them with its own counter:
  /// `-XX:MaxDirectMemorySize=64m` yields netty's `OutOfDirectMemoryError` instead of the JVM's
  /// `OutOfMemoryError`.
  ///
  /// So for the ingress and the upstream path, which both use a pooling allocator, netty's own
  /// allocator metrics are the only thing exposed via JMX. That is what [NettyAllocatorMetrics] is
  /// for.
  @Test
  void pooledAllocatorMemoryIsInvisibleToTheJvmBufferPool() {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    // let the allocator set up its arenas and thread caches first
    ByteBuf dummyBuf = PooledByteBufAllocator.DEFAULT.directBuffer(123);
    dummyBuf.release();
    Snapshots s0 = snapshots();
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(sampleAllocation);
    try {
      Snapshots s1 = snapshots();
      printMetrics(s1);
      assertThat(s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
          .as("the JVM direct pool does not see the chunks of the pooled allocator")
          .isLessThan(
              s0.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue()
                  + sampleAllocation / 2);
      assertThat(PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory())
          .as("netty's own metric does see it")
          .isGreaterThanOrEqualTo(sampleAllocation);
    } finally {
      buf.release();
    }
  }

  /// Where the memory of the pooling allocators *is* visible: HotSpot's native memory tracking,
  /// category `Other`, which is where the `java.lang.foreign.Arena` allocations land. Nothing had
  /// to be written for this, the prometheus client exposes it out of the box: `JvmMetrics`
  /// registers `JvmNativeMemoryMetrics`, which reads the diagnostic command in process, and exposes
  /// no series at all when the tracking is off.
  ///
  /// This needs `-XX:NativeMemoryTracking=summary`, which surefire sets for us. It costs 5 to 10
  /// percent of JVM performance, so it is not enabled in production, see the README. Measured with
  /// 100 MiB from the pooled allocator, `Other` goes `910_432` -> `105_768_040`, a delta of
  /// `104_857_608`, while `jvm_buffer_pool_used_bytes{pool="direct"}` moves by `1`. After the
  /// release `Other` returns to `910_440`, because an allocation of this size bypasses the pooling
  /// as a huge chunk and is given back immediately.
  @Test
  void nativeMemoryTrackingSeesPooledAllocatorMemory() {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    Snapshots s0 = snapshots();
    assumeTrue(
        s0.getPrometheusExpositionText().contains("jvm_native_memory_committed_bytes"),
        "needs -XX:NativeMemoryTracking=summary");
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(sampleAllocation);
    try {
      Snapshots s1 = snapshots();
      printMetrics(s1);
      assertThat(s1.gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue())
          .as("native memory tracking sees the allocation")
          .isGreaterThanOrEqualTo(
              s0.gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue()
                  + sampleAllocation);
      assertThat(s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
          .as("while the JVM direct buffer pool still does not")
          .isLessThan(
              s0.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue()
                  + sampleAllocation / 2);
    } finally {
      buf.release();
    }
  }

  /// [AdaptiveByteBufAllocator] is netty's default allocator and the one our server side uses,
  /// since [Proxy] sets no `ChannelOption.ALLOCATOR`. Its metric has to account for large buffers,
  /// because for the ingress path it is the only accounting we have.
  ///
  /// With netty 4.2.3 it did not, buffers above 1 MiB bypassed the magazine and chunk accounting
  /// completely. Measured with a fresh allocator per size:
  ///
  /// ```
  /// requested  512 KiB -> usedDirectMemory 4_194_304  tracked, rounded up to the chunk size
  /// requested 1024 KiB -> usedDirectMemory 8_388_608  tracked
  /// requested 1536 KiB -> usedDirectMemory         0  not tracked
  /// requested 8192 KiB -> usedDirectMemory         0  not tracked
  /// ```
  ///
  /// Netty 4.2.16 reworked the chunk management and reports every size exactly, `2_097_152` for the
  /// allocation below and back to `0` after the release.
  @Test
  void adaptiveAllocatorMetricTracksLargeBuffers() {
    final int sampleAllocation = 2 * 1024 * 1024;
    AdaptiveByteBufAllocator allocator = new AdaptiveByteBufAllocator();
    ByteBufAllocatorMetric metric = allocator.metric();
    long usedBefore = metric.usedDirectMemory();
    ByteBuf buf = allocator.directBuffer(sampleAllocation);
    try {
      assertThat(metric.usedDirectMemory())
          .as("allocator accounts for a buffer of %s bytes", sampleAllocation)
          .isGreaterThanOrEqualTo(usedBefore + sampleAllocation);
    } finally {
      buf.release();
    }
  }

  /// Netty cannot free an `allocateDirect` buffer eagerly without `Unsafe.invokeCleaner`, so the
  /// memory returns to the direct pool only once the buffer is collected.
  private void awaitDirectPoolBelow(double expected) throws Exception {
    for (int i = 0; i < 50; i++) {
      if (snapshots().gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue()
          <= expected) {
        return;
      }
      System.gc();
      Thread.sleep(20);
    }
  }

  static boolean silent = false;

  private static void printMetrics(Snapshots s) {
    if (silent) {
      return;
    }
    System.out.println(s.getLinesContaining("jvm_buffer_pool_used_bytes"));
    System.out.println(s.getLinesContaining("jvm_memory_used_bytes"));
    System.out.println(s.getLinesContaining("process_resident_memory_bytes"));
    System.out.println(s.getLinesContaining("process_virtual_memory_bytes"));
    System.out.println(s.getLinesContaining("jvm_native_memory_committed_bytes{pool=\"Other\"}"));
  }

  Snapshots snapshots() {
    return new Snapshots(reg.scrape());
  }

  static class Snapshots {

    MetricSnapshots snapshots;
    Map<String, MetricSnapshot> name2snapshot = new HashMap<>();
    String prometheusText;

    Snapshots(MetricSnapshots snapshots) {
      this.snapshots = snapshots;
      snapshots.forEach(
          snapshot -> {
            name2snapshot.put(snapshot.getMetadata().getName(), snapshot);
          });
      PrometheusTextFormatWriter pw = PrometheusTextFormatWriter.builder().build();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        pw.write(out, snapshots);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      prometheusText = out.toString();
    }

    MetricSnapshot containsSnapshot(String metricName) {
      MetricSnapshot snapshot = name2snapshot.get(metricName);
      assertThat(snapshot).isNotNull().describedAs("snapshot available for metric %s", metricName);
      return snapshot;
    }

    DataPointSnapshot datapoint(String metricName) {
      MetricSnapshot snapshot = containsSnapshot(metricName);
      assertThat(snapshot.getDataPoints().size()).isEqualTo(1);
      DataPointSnapshot dataPointSnapshot = snapshot.getDataPoints().getFirst();
      return dataPointSnapshot;
    }

    DataPointSnapshot datapoint(String metricName, String... labelKeyValues) {
      Labels labels = Labels.of(labelKeyValues);
      MetricSnapshot snapshot = containsSnapshot(metricName);
      return snapshot.getDataPoints().stream()
          .filter(dataPointSnapshot -> labels.equals(dataPointSnapshot.getLabels()))
          .findFirst()
          .get();
    }

    GaugeSnapshot.GaugeDataPointSnapshot gauge(String metricName, String... labelKeyValues) {
      return (GaugeSnapshot.GaugeDataPointSnapshot) datapoint(metricName, labelKeyValues);
    }

    CounterSnapshot.CounterDataPointSnapshot counter(String metricName) {
      return (CounterSnapshot.CounterDataPointSnapshot) datapoint(metricName);
    }

    GaugeSnapshot.GaugeDataPointSnapshot gouge(String metricName) {
      return (GaugeSnapshot.GaugeDataPointSnapshot) datapoint(metricName);
    }

    String getPrometheusExpositionText() {
      return prometheusText;
    }

    String getLinesContaining(String match) {
      return new BufferedReader(new StringReader(prometheusText))
          .lines()
          .filter(line -> line.contains(match))
          .collect(Collectors.joining("\n"));
    }

    @Override
    public String toString() {
      return prometheusText;
    }
  }
}
