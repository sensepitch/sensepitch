package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class AsyncRequestLogWriterTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void linesAreWrittenInOrderAndCloseWritesOutEverything() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(out, 1024);
    for (int i = 0; i < 1000; i++) {
      writer.writeLine("line " + i + " äöü");
    }
    writer.close();
    var lines = out.toString(StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).hasSize(1000);
    assertThat(lines.getFirst()).isEqualTo("line 0 äöü");
    assertThat(lines.getLast()).isEqualTo("line 999 äöü");
    assertThat(writer.droppedCount()).isZero();
    assertThat(logs.errors()).isEmpty();
  }

  @Test
  public void recordIsVisibleWithoutCloseWhenIdle() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(out, 16);
    writer.writeLine("single");
    while (out.size() == 0) {
      Thread.sleep(1);
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("single\n");
    writer.close();
  }

  /// The point of the writer: a stuck standard out must not block the calling event loop.
  @Test
  public void blockedOutputDropsInsteadOfBlockingTheCaller() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream blocking =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            sink.write(b, off, len);
          }
        };
    int capacity = 4;
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(blocking, capacity);
    long start = System.nanoTime();
    for (int i = 0; i < 100; i++) {
      writer.writeLine("line " + i);
    }
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .describedAs("caller must not wait for the output")
        .isLessThan(2_000);
    // the writer thread holds at most one drained batch (capacity + 1) while blocked, and the
    // queue another capacity records, everything else is dropped
    assertThat(writer.droppedCount()).isGreaterThanOrEqualTo(100 - 2 * capacity - 1);
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .describedAs("first drop is reported once")
        .singleElement()
        .asString()
        .contains("dropping records");
    release.countDown();
    writer.close();
    long written = sink.toString(StandardCharsets.UTF_8).lines().count();
    assertThat(written + writer.droppedCount()).isEqualTo(100);
  }

  @Test
  public void writeAfterCloseIsDroppedSilently() {
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(new ByteArrayOutputStream(), 4);
    writer.close();
    writer.close();
    writer.writeLine("late");
    assertThat(writer.droppedCount()).isOne();
    assertThat(logs.errors()).isEmpty();
  }

  @Test
  public void outputErrorIsReportedOnceAndDoesNotReachTheCaller() {
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("broken pipe");
          }

          @Override
          public void flush() throws IOException {
            throw new IOException("broken pipe");
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(failing, 16);
    for (int i = 0; i < 10; i++) {
      writer.writeLine("line " + i);
    }
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("Error writing request log");
  }

  /// A record larger than the output buffer is written directly, so the error comes from the
  /// write, not from the flush.
  @Test
  public void errorWritingLargeRecordIsReported() {
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("broken pipe");
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("broken pipe");
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(failing, 16);
    writer.writeLine("x".repeat(AsyncRequestLogWriter.BUFFER_SIZE + 1));
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("Error writing request log");
  }

  /// The writer exposes dropped records and the queue length. In production the writer is the
  /// request log output, so the proxy registers these, see [Proxy].
  @Test
  public void metrics() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    OutputStream blocking =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(blocking, 2);
    PrometheusRegistry registry = new PrometheusRegistry();
    writer.registerCollectors(registry::register);
    for (int i = 0; i < 10; i++) {
      writer.writeLine("x".repeat(AsyncRequestLogWriter.BUFFER_SIZE + 1));
    }
    var snapshots = new MetricsAndMemoryTest.Snapshots(registry.scrape());
    assertThat(snapshots.counter("sensepitch_request_log_dropped").getValue())
        .isEqualTo(writer.droppedCount())
        .isPositive();
    assertThat(snapshots.gauge("sensepitch_request_log_queued").getValue()).isBetween(0.0, 2.0);
    release.countDown();
    writer.close();
  }
}
