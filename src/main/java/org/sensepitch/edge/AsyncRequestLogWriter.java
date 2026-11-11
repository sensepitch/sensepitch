package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.model.registry.Collector;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Writes the request log to a stream, usually standard out, from a dedicated thread, so event
/// loops never block on I/O.
///
/// Why: with a shared buffered stream, the event loop that fills the buffer does the `write`
/// system call while holding the stream lock. If standard out is a pipe and the log collector is
/// slow, that call blocks, and every other event loop that wants to log a request queues up behind
/// the lock. The access log would stall the proxy.
///
/// How:
///
/// - Event loops encode the line to bytes and put them into a bounded queue. That is the only
///   shared state, and the critical section of the queue is tiny.
/// - The writer thread takes everything that is queued, writes it into its own buffer and
///   flushes once the queue is empty. Under load records are batched into few large writes, when
///   idle each record is visible immediately. No timer is needed.
/// - When the queue is full, the record is dropped and counted in the metric
///   `sensepitch_request_log_dropped_total`. Losing access-log lines is preferable to stalling
///   traffic. The first drop is also reported as an error.
/// - [#close()] writes out everything queued before and stops the thread.
///
/// @author Jens Wilke
public class AsyncRequestLogWriter
    implements RequestLogOutput, RequestLogMetrics.QueueSource, HasMultipleMetrics {

  /// Name of the destination, the metric label
  public static final String STDOUT = "stdout";

  /// Default queue capacity, at about 1000 bytes per record this caps the memory at a few
  /// megabytes
  public static final int DEFAULT_CAPACITY = 8 * 1024;

  /// Size of the output buffer of the writer thread
  static final int BUFFER_SIZE = 64 * 1024;

  private static final ProxyLogger LOG = ProxyLogger.get(AsyncRequestLogWriter.class);

  /// Marks the end of the queue, compared by identity
  private static final byte[] END = new byte[0];

  private static final long CLOSE_TIMEOUT_MILLIS = 5_000;

  private final String destination;
  private final BlockingQueue<byte[]> queue;
  private final OutputStream output;
  private final RequestLogMetrics metrics;
  private final CounterDataPoint dropped;
  private final Thread thread;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean dropReported = new AtomicBoolean();
  private final AtomicBoolean errorReported = new AtomicBoolean();

  /// Writer to standard out with the default capacity
  public static AsyncRequestLogWriter standardOut() {
    return new AsyncRequestLogWriter(new FileOutputStream(FileDescriptor.out), DEFAULT_CAPACITY);
  }

  /// Writer with its own metrics, named [#STDOUT]
  public AsyncRequestLogWriter(OutputStream output, int capacity) {
    this(STDOUT, output, capacity, new RequestLogMetrics());
  }

  public AsyncRequestLogWriter(
      String destination, OutputStream output, int capacity, RequestLogMetrics metrics) {
    this.destination = destination;
    this.output = new BufferedOutputStream(output, BUFFER_SIZE);
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.metrics = metrics;
    metrics.add(this);
    dropped = metrics.dropped.labelValues(destination);
    thread = new Thread(this::run, "request-log-" + destination);
    // must not keep the JVM alive, close() makes sure nothing is lost on a regular shutdown
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public String destination() {
    return destination;
  }

  @Override
  public int queueSize() {
    return queue.size();
  }

  /// Metrics shared by all destinations of the proxy
  public RequestLogMetrics metrics() {
    return metrics;
  }

  /// Registers the shared metrics, used when the writer is the only destination, e.g. in tests
  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    metrics.registerCollectors(consumer);
  }

  /// Number of dropped records since start
  public long droppedCount() {
    return (long) dropped.get();
  }

  @Override
  public void writeLine(String line) {
    // encoding happens here, in the calling event loop, to keep the writer thread lean
    if (closed.get()) {
      // event loops still finishing requests during shutdown, not a capacity problem
      dropped.inc();
      return;
    }
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    if (!queue.offer(bytes)) {
      dropped.inc();
      if (dropReported.compareAndSet(false, true)) {
        LOG.error(
            "Request log cannot keep up, dropping records."
                + " Further drops are only counted in sensepitch_request_log_dropped_total");
      }
    }
  }

  /// Writes out the records queued so far and stops the writer thread. Waits at most a few
  /// seconds, so a blocked standard out cannot prevent the shutdown. Can be called more than once.
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      if (!queue.offer(END, CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        LOG.error("Request log writer is blocked, closing without writing out pending records");
        return;
      }
      thread.join(CLOSE_TIMEOUT_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void run() {
    List<byte[]> batch = new ArrayList<>();
    try {
      while (true) {
        batch.add(queue.take());
        queue.drainTo(batch);
        for (byte[] bytes : batch) {
          // a record after the end marker was queued by an event loop racing with close, it is
          // lost like a record written after close
          if (bytes == END) {
            flush();
            return;
          }
          write(bytes);
        }
        batch.clear();
        // queue was empty when drained, so nothing more is pending right now
        flush();
      }
    } catch (InterruptedException e) {
      flush();
    }
  }

  private void write(byte[] bytes) {
    try {
      output.write(bytes);
      output.write('\n');
    } catch (IOException e) {
      reportError(e);
    }
  }

  private void flush() {
    try {
      output.flush();
    } catch (IOException e) {
      reportError(e);
    }
  }

  /// Keeps draining the queue after an error, so event loops are never stuck. Reported once only,
  /// since a broken standard out would otherwise produce an error per request.
  private void reportError(IOException e) {
    if (errorReported.compareAndSet(false, true)) {
      LOG.error("Error writing request log, further errors are suppressed", e);
    }
  }
}
