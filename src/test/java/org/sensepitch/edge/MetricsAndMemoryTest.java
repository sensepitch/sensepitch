package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.AdaptiveByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
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
import java.lang.foreign.Arena;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
  /// buffers that netty is using. With `--enable-native-access=ALL-UNNAMED`, which is the
  /// production configuration, netty allocates *unpooled* direct buffers through
  /// `java.lang.foreign`: the `CleanerJava24Linker` links `malloc` and `free` from libc directly.
  /// That memory is intentionally outside the accounting of `java.nio`, so it does not appear in
  /// `jvm_buffer_pool_used_bytes{pool="direct"}`. Netty's own metric, see
  /// [UnpooledByteBufAllocator#metric], and `process_virtual_memory_bytes`, see
  /// [#virtualMemoryMetricReflectsDirectByteBuffer], do see it. Resident memory does not move here,
  /// because the buffer is never touched and `malloc` only reserves address space.
  ///
  /// Without native access netty falls back to `ByteBuffer.allocateDirect`, which *is* visible in
  /// the buffer pool. That was the behaviour this test originally asserted; it changed when native
  /// access was enabled for the test JVM. The pooled and adaptive allocators are likewise
  // invisible,
  /// see [#pooledAllocatorMemoryIsInvisibleToTheJvmBufferPool].
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
    Snapshots s0 = snapshots();
    printMetrics(s0);
    ByteBufAllocatorMetric metric = UnpooledByteBufAllocator.DEFAULT.metric();
    long usedBefore = metric.usedDirectMemory();
    new NettyAllocatorMetrics().registerCollectors(reg::register);
    ByteBuf buf = Unpooled.directBuffer(sampleAllocation);
    try {
      Snapshots s1 = snapshots();
      printMetrics(s1);
      assertThat(s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
          .as("the JVM direct pool does not see buffers allocated through java.lang.foreign")
          .isLessThan(
              s0.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue()
                  + sampleAllocation / 2);
      assertThat(metric.usedDirectMemory())
          .as("netty's own metric sees the unpooled allocation")
          .isEqualTo(usedBefore + sampleAllocation);
      assertThat(unpooledDirectMemory()).isEqualTo(usedBefore + sampleAllocation);
    } finally {
      buf.release();
    }
    assertThat(metric.usedDirectMemory())
        .as("release restores allocator accounting")
        .isEqualTo(usedBefore);
    assertThat(unpooledDirectMemory())
        .as("the exported gauge reflects release")
        .isEqualTo(usedBefore);
  }

  private double unpooledDirectMemory() {
    return snapshots()
        .gauge(NettyAllocatorMetrics.METRIC_NAME, "allocator", "unpooled", "type", "direct")
        .getValue();
  }

  /// Preserve the positive JVM-pool and reclamation checks independently of Netty's cleaner choice.
  @Test
  void jvmDirectBufferMetricTracksAllocationAndCollection() throws Exception {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    double before = snapshots().gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue();
    allocateJvmDirectBuffer(sampleAllocation, before);
    for (int i = 0; i < 100; i++) {
      System.gc();
      if (snapshots().gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue() <= before) {
        return;
      }
      Thread.sleep(20);
    }
    assertThat(snapshots().gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
        .as("collected direct buffer memory returns to the JVM pool")
        .isLessThanOrEqualTo(before);
  }

  private void allocateJvmDirectBuffer(int size, double before) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(size);
    try {
      assertThat(snapshots().gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
          .as("the JVM direct pool sees ByteBuffer.allocateDirect")
          .isGreaterThanOrEqualTo(before + size);
    } finally {
      Reference.reachabilityFence(buffer);
    }
  }

  /// Run in a fresh JVM so unrelated tests and heap reclamation cannot drown out the RSS signal.
  @Test
  void residentMemoryMetricReflectsTouchedDirectByteBuffer() throws Exception {
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Xms64m",
                "-Xmx64m",
                "-XX:+AlwaysPreTouch",
                "-XX:MaxDirectMemorySize=256m",
                "-cp",
                System.getProperty("java.class.path"),
                ResidentMemoryProbe.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("RSS probe completes").isTrue();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as("RSS probe output: %s", output).isZero();
    } finally {
      process.destroyForcibly();
    }
  }

  public static class ResidentMemoryProbe {
    public static void main(String[] args) {
      MetricsAndMemoryTest test = new MetricsAndMemoryTest();
      JvmMetrics.builder().register(test.reg);
      ByteBuf warmup = Unpooled.directBuffer(123);
      warmup.release();
      test.snapshots();
      double before = test.snapshots().gauge("process_resident_memory_bytes").getValue();
      final int sampleAllocation = 100 * 1024 * 1024;
      ByteBuf buf = Unpooled.directBuffer(sampleAllocation);
      try {
        // malloc does not make the pages resident until they are written.
        buf.setZero(0, buf.capacity());
        assertThat(test.snapshots().gauge("process_resident_memory_bytes").getValue())
            .as("RSS sees touched native memory")
            .isGreaterThanOrEqualTo(before + sampleAllocation / 2);
      } finally {
        buf.release();
      }
    }
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

  /// With native access enabled, the pooling allocators also allocate through libc. Without native
  /// access they can use a shared `java.lang.foreign.Arena`. Neither path appears in the JVM direct
  /// buffer pool. Netty's allocator accounting sees the allocation in either case.
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

  /// With native access enabled Netty calls libc directly, bypassing HotSpot's NMT accounting too.
  /// Keep the positive Arena control below so an inactive NMT collector cannot satisfy this test.
  @Test
  void nativeMemoryTrackingDoesNotSeeLinkedPooledAllocatorMemory() {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    Snapshots s0 = snapshots();
    assertThat(s0.getPrometheusExpositionText().contains("jvm_native_memory_committed_bytes"))
        .as("needs -XX:NativeMemoryTracking=summary")
        .isTrue();
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(sampleAllocation);
    try {
      Snapshots s1 = snapshots();
      printMetrics(s1);
      assertThat(s1.gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue())
          .as("native memory tracking does not see direct libc allocations")
          .isLessThan(
              s0.gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue()
                  + sampleAllocation / 2);
      assertThat(s1.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue())
          .as("while the JVM direct buffer pool still does not")
          .isLessThan(
              s0.gauge("jvm_buffer_pool_used_bytes", "pool", "direct").getValue()
                  + sampleAllocation / 2);
    } finally {
      buf.release();
    }
  }

  /// Arena allocations go through HotSpot and remain visible in NMT's `Other` category.
  @Test
  void nativeMemoryTrackingSeesArenaMemory() {
    JvmMetrics.builder().register(reg);
    final int sampleAllocation = 100 * 1024 * 1024;
    double before =
        snapshots().gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue();
    try (Arena arena = Arena.ofShared()) {
      var memory = arena.allocate(sampleAllocation);
      assertThat(snapshots().gauge("jvm_native_memory_committed_bytes", "pool", "Other").getValue())
          .as("NMT sees allocations made through the JVM")
          .isGreaterThanOrEqualTo(before + sampleAllocation);
      Reference.reachabilityFence(memory);
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
    assertThat(metric.usedDirectMemory())
        .as("release restores allocator accounting")
        .isEqualTo(usedBefore);
  }

  private static final Logger LOG = LogManager.getLogger(MetricsAndMemoryTest.class);

  /// Diagnostic output, enable with level `DEBUG` for this class in `log4j2-test.xml`
  private static void printMetrics(Snapshots s) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    LOG.debug(s.getLinesContaining("jvm_buffer_pool_used_bytes"));
    LOG.debug(s.getLinesContaining("jvm_memory_used_bytes"));
    LOG.debug(s.getLinesContaining("process_resident_memory_bytes"));
    LOG.debug(s.getLinesContaining("process_virtual_memory_bytes"));
    LOG.debug(s.getLinesContaining("jvm_native_memory_committed_bytes{pool=\"Other\"}"));
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
