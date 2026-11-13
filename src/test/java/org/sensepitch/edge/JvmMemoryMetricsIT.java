package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// JVM memory metrics that need garbage collection or a forked JVM. They check the behaviour of
/// the JVM and Netty rather than our code, and are slow, so they run with `verify` only. The fast
/// checks are in [MetricsAndMemoryTest].
///
/// @author Jens Wilke
class JvmMemoryMetricsIT {

  PrometheusRegistry reg = new PrometheusRegistry();

  MetricsAndMemoryTest.Snapshots snapshots() {
    return new MetricsAndMemoryTest.Snapshots(reg.scrape());
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
      JvmMemoryMetricsIT test = new JvmMemoryMetricsIT();
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
}
