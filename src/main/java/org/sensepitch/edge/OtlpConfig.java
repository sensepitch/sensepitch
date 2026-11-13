package org.sensepitch.edge;

import io.netty.util.NettyRuntime;
import io.netty.util.internal.SystemPropertyUtil;
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
///   records into a buffer of its own and posts it when it reaches this size. When the flush
///   interval is over, the partly filled buffers of all threads are posted together, a request
///   carries up to this size then. Each thread holds one buffer of this size, plus a few recycled
///   ones are kept. Defaults to {@value #DEFAULT_BATCH_BYTES}, the minimum is
///   {@value #MIN_BATCH_BYTES}.
/// @param maxBufferedBytes maximum size of all batches waiting to be sent, e.g. while the log
///   server is restarted. Failed requests are retried, so the batches pile up until the server is
///   back. When the limit is reached, new records are dropped and counted, the waiting batches are
///   kept. The minimum, and the default, is `2 × event loops × batchBytes`: every event loop can
///   have one full batch waiting or in flight and one being filled. A lower value is not
///   respected, the minimum is used and a warning is logged, see [#minMaxBufferedBytes()].
/// @param maxConcurrentRequests requests posted at the same time; more batches wait until a
///   request completes. Batches may arrive out of order. Defaults to the number of event loops,
///   i.e. two per processor, so every event loop can have a full batch in flight.
/// @param maximumUnsentLogAgeMillis a batch whose first record is older is dropped and counted.
///   This ends the retries for a persistent error, e.g. a wrong endpoint or a log server that is
///   gone for good, and bounds the time a destination replaced by a reload keeps sending its
///   buffered records. Defaults to {@value #DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS}.
/// @param compression `gzip` (default) or `none`. Gzip shrinks the posts several times, the
///   records repeat hosts, attribute keys and user agents. The compression runs on a thread of
///   its own, never on an event loop. `none` is for a log server that does not accept
///   `Content-Encoding: gzip`. The sizes above, e.g. `batchBytes`, are uncompressed.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record OtlpConfig(
    String endpoint,
    Map<String, String> headers,
    Map<String, String> resourceAttributes,
    int timeoutMillis,
    int flushIntervalMillis,
    int batchBytes,
    int maxBufferedBytes,
    int maxConcurrentRequests,
    int maximumUnsentLogAgeMillis,
    String compression) {

  public static final int DEFAULT_TIMEOUT_MILLIS = 10_000;
  public static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 1_000;
  public static final int DEFAULT_BATCH_BYTES = 1024 * 1024;
  public static final int DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS = 5 * 60 * 1000;

  public static final String GZIP = "gzip";
  public static final String NONE = "none";

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

  /// Limit of the waiting batches: the configured value, but never below the minimum, which is
  /// also the default
  public long effectiveMaxBufferedBytes() {
    return Math.max(maxBufferedBytes, minMaxBufferedBytes());
  }

  /// Lowest limit of the waiting batches: `2 × event loops × batchBytes`, so every event loop can
  /// have one full batch waiting or in flight and one being filled. A full batch is a bit larger
  /// than the batch size, so a limit near the batch size would drop every record.
  public long minMaxBufferedBytes() {
    return 2L * defaultEventLoopThreads() * effectiveBatchBytes();
  }

  /// Requests at the same time, or the default, the number of event loops Netty starts by default
  public int effectiveMaxConcurrentRequests() {
    return maxConcurrentRequests > 0 ? maxConcurrentRequests : defaultEventLoopThreads();
  }

  /// Maximum age of an unsent record, or the default if not configured
  public int effectiveMaximumUnsentLogAgeMillis() {
    return maximumUnsentLogAgeMillis > 0
        ? maximumUnsentLogAgeMillis
        : DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS;
  }

  /// The compression, `gzip` if not configured, see [#compression()]
  public String effectiveCompression() {
    return compression != null ? compression : GZIP;
  }

  /// Threads of an event loop group without explicit size, as computed by Netty's
  /// `MultithreadEventLoopGroup`
  static int defaultEventLoopThreads() {
    return Math.max(
        1,
        SystemPropertyUtil.getInt(
            "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));
  }
}
