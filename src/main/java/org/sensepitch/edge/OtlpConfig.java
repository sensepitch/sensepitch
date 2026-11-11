package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// Settings of an `otlp` request log destination, see [RequestLogDestinationConfig#otlp()] and
/// [OtlpLogDestination].
///
/// @param endpoint mandatory: URL the records are posted to, e.g.
///   `http://victorialogs:9428/insert/opentelemetry/v1/logs` or `http://collector:4318/v1/logs`
/// @param headers additional HTTP request headers, e.g. `Authorization` or the `VL-Stream-Fields`
///   header of VictoriaLogs
/// @param resourceAttributes attributes of the resource, the producer of the records.
///   `service.name` defaults to {@value OtlpLogDestination#DEFAULT_SERVICE_NAME}
/// @param timeoutMillis connect and request timeout, defaults to {@value #DEFAULT_TIMEOUT_MILLIS}
/// @param flushIntervalMillis maximum time a record waits before it is sent, defaults to
///   {@value #DEFAULT_FLUSH_INTERVAL_MILLIS}
/// @param batchBytes size of a batch: each thread that logs, i.e. each event loop, encodes its
///   records into a buffer of its own and hands it to the sender when it reaches this size, or
///   when the flush interval is over. A request carries one batch. Each thread holds one buffer of
///   this size, plus a few recycled ones are kept. Defaults to {@value #DEFAULT_BATCH_BYTES}, the
///   minimum is {@value #MIN_BATCH_BYTES}.
/// @param maxBufferedBytes maximum size of the batches waiting to be sent, e.g. while the log
///   server is restarted. Failed requests are retried, so the batches pile up until the server is
///   back. When the limit is reached, new records are dropped and counted, the waiting batches are
///   kept. Defaults to {@value #DEFAULT_MAX_BUFFERED_BYTES}, at least `batchBytes`.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record OtlpConfig(
    String endpoint,
    Map<String, String> headers,
    Map<String, String> resourceAttributes,
    int timeoutMillis,
    int flushIntervalMillis,
    int batchBytes,
    int maxBufferedBytes) {

  public static final int DEFAULT_TIMEOUT_MILLIS = 10_000;
  public static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 1_000;
  public static final int DEFAULT_BATCH_BYTES = 1024 * 1024;
  public static final int DEFAULT_MAX_BUFFERED_BYTES = 64 * 1024 * 1024;

  /// A few dozen records at least
  public static final int MIN_BATCH_BYTES = 64 * 1024;

  /// Timeout, or the default if not configured
  public int effectiveTimeoutMillis() {
    return timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
  }

  /// Flush interval, or the default if not configured
  public int effectiveFlushIntervalMillis() {
    return flushIntervalMillis > 0 ? flushIntervalMillis : DEFAULT_FLUSH_INTERVAL_MILLIS;
  }

  /// Batch size, or the default if not configured
  public int effectiveBatchBytes() {
    return batchBytes > 0 ? batchBytes : DEFAULT_BATCH_BYTES;
  }

  /// Limit of the waiting batches, or the default if not configured
  public long effectiveMaxBufferedBytes() {
    return maxBufferedBytes > 0 ? maxBufferedBytes : DEFAULT_MAX_BUFFERED_BYTES;
  }
}
