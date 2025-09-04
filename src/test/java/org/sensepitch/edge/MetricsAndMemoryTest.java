package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
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

/**
 * Test our understanding of the JVM memory metrics w.r.t. to the Netty byte buffers.
 *
 * @author Jens Wilke
 */
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

  /**
   * The general Java memory metrics jvm_memory_used_bytes do not reflect the usage of the byte
   * buffers that netty is using. The buffers are reflected in the metric
   * jvm_buffer_pool_used_bytes{pool="direct"} also in the total RSS memory consumption.
   */
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
        .as("resident memory grows by at least %s", sampleAllocation)
        .isGreaterThanOrEqualTo(
            s0.gauge("process_resident_memory_bytes").getValue() + sampleAllocation);
    // zeroing the buffer does not have an effect
    // for (int i = 0; i < buf.capacity(); i += 4096) { // touch each 4 KB page
    //   buf.setByte(i, (byte) 0);
    // }
    buf.release();
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

  static boolean silent = false;

  private static void printMetrics(Snapshots s) {
    if (silent) {
      return;
    }
    System.out.println(s.getLinesContaining("jvm_buffer_pool_used_bytes"));
    System.out.println(s.getLinesContaining("jvm_memory_used_bytes"));
    System.out.println(s.getLinesContaining("process_resident_memory_bytes"));
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
