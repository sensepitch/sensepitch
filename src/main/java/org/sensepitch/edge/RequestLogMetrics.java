package org.sensepitch.edge;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.model.registry.Collector;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// Metrics of all request log destinations, labelled with the destination name. One instance is
/// shared by the writers of a proxy, because a metric name can be registered only once.
///
/// @author Jens Wilke
public class RequestLogMetrics implements HasMultipleMetrics {

  static final String DESTINATION_LABEL = "destination";

  /// Something that holds request log records before writing them, for the queue length metric
  public interface QueueSource {

    /// Name of the destination, the label value
    String destination();

    /// Records waiting to be written
    int queueSize();

    /// Bytes of the records waiting to be sent, negative if not tracked
    default long bufferedBytes() {
      return -1;
    }
  }

  private final List<QueueSource> writers = new CopyOnWriteArrayList<>();

  final Counter dropped =
      Counter.builder()
          .name("sensepitch_request_log_dropped")
          .help("Request log records dropped because the destination could not keep up")
          .labelNames(DESTINATION_LABEL)
          .build();

  final Counter exportFailed =
      Counter.builder()
          .name("sensepitch_request_log_export_failed")
          .help("Batches of request log records given up, because retrying cannot succeed")
          .labelNames(DESTINATION_LABEL)
          .build();

  final Counter exportRetries =
      Counter.builder()
          .name("sensepitch_request_log_export_retries")
          .help("Failed attempts to send a batch of request log records that are retried")
          .labelNames(DESTINATION_LABEL)
          .build();

  private final GaugeWithCallback bufferedBytes =
      GaugeWithCallback.builder()
          .name("sensepitch_request_log_buffered_bytes")
          .help("Bytes of request log records waiting to be sent, e.g. during an outage")
          .labelNames(DESTINATION_LABEL)
          .callback(
              cb -> {
                for (QueueSource writer : writers) {
                  long bytes = writer.bufferedBytes();
                  if (bytes >= 0) {
                    cb.call(bytes, writer.destination());
                  }
                }
              })
          .build();

  private final GaugeWithCallback queued =
      GaugeWithCallback.builder()
          .name("sensepitch_request_log_queued")
          .help("Request log records waiting to be written")
          .labelNames(DESTINATION_LABEL)
          .callback(
              cb -> {
                for (QueueSource writer : writers) {
                  cb.call(writer.queueSize(), writer.destination());
                }
              })
          .build();

  void add(QueueSource writer) {
    writers.add(writer);
    // creates the data point, so it is exported with zero before the first drop
    dropped.labelValues(writer.destination());
  }

  /// Stops reporting the queue of a destination that was closed, e.g. removed by a configuration
  /// reload. Its counters keep their values.
  void remove(QueueSource writer) {
    writers.remove(writer);
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(dropped);
    consumer.accept(exportFailed);
    consumer.accept(exportRetries);
    consumer.accept(bufferedBytes);
    consumer.accept(queued);
  }
}
