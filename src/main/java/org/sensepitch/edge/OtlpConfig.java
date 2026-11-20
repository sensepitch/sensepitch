package org.sensepitch.edge;

import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import lombok.Builder;

/// Settings of an `otlp` request log destination, see [RequestLogDestinationConfig#otlp()] and
/// [OtlpLogDestination]. A built record holds the values used, see [#builder(ProxyEnvironment)].
/// Timeouts, intervals, batch size, and concurrency must be positive when specified.
///
/// @param endpoint mandatory: URL the records are posted to, e.g.
///   `http://victorialogs:9428/insert/opentelemetry/v1/logs` or `http://collector:4318/v1/logs`
/// @param headers additional HTTP request headers, e.g. the `VL-Stream-Fields` header of
///   VictoriaLogs
/// @param secretHeaders additional HTTP request headers whose values are secrets, e.g.
///   `Authorization`, read from a file or given inline, see [SecretHeaderConfig]. A header must
///   not be in both `headers` and `secretHeaders`. A changed secret file replaces the destination
///   on the next reload, like a changed configuration.
/// @param resourceAttributes attributes of the resource, the producer of the records.
///   `service.name` defaults to {@value OtlpLogDestination#DEFAULT_SERVICE_NAME}
/// @param timeoutMillis connect and request timeout, defaults to {@value #DEFAULT_TIMEOUT_MILLIS}
/// @param initialRetryDelayMillis nominal delay after the first retryable failure, in milliseconds;
///   omitted uses {@value #DEFAULT_INITIAL_RETRY_DELAY_MILLIS}.
/// @param maxRetryDelayMillis maximum nominal exponential retry delay, in milliseconds; omitted
///   uses {@value #DEFAULT_MAX_RETRY_DELAY_MILLIS}. Must not be below the initial delay. Jitter
///   takes a random half of the nominal delay; a longer `Retry-After` can exceed this maximum.
///   Changing either delay on reload replaces the destination; buffered records drain with the
///   old settings, see [ReloadableRequestLogger]. A maximum above `maximumUnsentLogAgeMillis`
///   lets batches age out between two probes, and the end of an outage is noticed late.
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
///   have one full batch waiting or in flight and one being filled. The default is derived from
///   the final `batchBytes` when the record is built, see [Builder#build()]. A lower value is not
///   respected, the minimum is used and a warning is logged, see [#minMaxBufferedBytes()].
/// @param maxConcurrentRequests requests posted at the same time; more batches wait until a
///   request completes. With one request at a time batches arrive in the order they were
///   handed over; with more, they may arrive out of order. Defaults to the number of event loops,
///   i.e. two per processor, so every event loop can have a full batch in flight.
/// @param maximumUnsentLogAgeMillis a batch whose first record is older is dropped and counted.
///   This ends the retries for a persistent error, e.g. a wrong endpoint or a log server that is
///   gone for good, and bounds the time a destination replaced by a reload keeps sending its
///   buffered records. Defaults to {@value #DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS}.
/// @param compression `gzip` (default) or `none`. Gzip shrinks the posts several times, the
///   records repeat hosts, attribute keys and user agents. The compression runs on a thread of
///   its own, never on an event loop. `none` is for a log server that does not accept
///   `Content-Encoding: gzip`. The sizes above, e.g. `batchBytes`, are uncompressed.
/// @param compressionLevel gzip level from {@value #MIN_COMPRESSION_LEVEL} (fastest) to
///   {@value #MAX_COMPRESSION_LEVEL} (smallest); omitted or zero uses
///   {@value #DEFAULT_COMPRESSION_LEVEL}. Only used with `compression: gzip`.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record OtlpConfig(
    String endpoint,
    Map<String, String> headers,
    List<SecretHeaderConfig> secretHeaders,
    Map<String, String> resourceAttributes,
    int timeoutMillis,
    int initialRetryDelayMillis,
    int maxRetryDelayMillis,
    int flushIntervalMillis,
    int batchBytes,
    long maxBufferedBytes,
    int maxConcurrentRequests,
    int maximumUnsentLogAgeMillis,
    String compression,
    int compressionLevel) {

  public static final int DEFAULT_TIMEOUT_MILLIS = 10_000;
  public static final int DEFAULT_INITIAL_RETRY_DELAY_MILLIS = 1_000;
  public static final int DEFAULT_MAX_RETRY_DELAY_MILLIS = 30_000;
  public static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 1_000;
  public static final int DEFAULT_BATCH_BYTES = 1024 * 1024;
  public static final int DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS = 5 * 60 * 1000;

  public static final String GZIP = "gzip";
  public static final String NONE = "none";

  /// The gzip level used when the configuration does not select another one
  public static final int DEFAULT_COMPRESSION_LEVEL = Deflater.BEST_SPEED;

  /// The fastest level exposed to the configuration
  public static final int MIN_COMPRESSION_LEVEL = Deflater.BEST_SPEED;

  /// The smallest output, at the highest CPU cost
  public static final int MAX_COMPRESSION_LEVEL = Deflater.BEST_COMPRESSION;

  /// A few dozen records at least
  public static final int MIN_BATCH_BYTES = 64 * 1024;

  /// Builder with the defaults of the environment of this JVM, see
  /// [#builder(ProxyEnvironment)]
  public static Builder builder() {
    return builder(ProxyEnvironment.current());
  }

  /// Builder with the defaults preset. `maxConcurrentRequests` defaults to the event loops of the
  /// environment, `maxBufferedBytes` is derived from the final `batchBytes` by [Builder#build()].
  public static Builder builder(ProxyEnvironment environment) {
    Builder builder =
        new Builder()
            .timeoutMillis(DEFAULT_TIMEOUT_MILLIS)
            .initialRetryDelayMillis(DEFAULT_INITIAL_RETRY_DELAY_MILLIS)
            .maxRetryDelayMillis(DEFAULT_MAX_RETRY_DELAY_MILLIS)
            .flushIntervalMillis(DEFAULT_FLUSH_INTERVAL_MILLIS)
            .batchBytes(DEFAULT_BATCH_BYTES)
            .maxConcurrentRequests(environment.eventLoopThreads())
            .maximumUnsentLogAgeMillis(DEFAULT_MAXIMUM_UNSENT_LOG_AGE_MILLIS)
            .compression(GZIP)
            .compressionLevel(DEFAULT_COMPRESSION_LEVEL);
    builder.environment = environment;
    return builder;
  }

  /// Lowest limit of the waiting batches in this JVM: `2 × event loops × batchBytes`, so every
  /// event loop can have one full batch waiting or in flight and one being filled. A full batch
  /// is a bit larger than the batch size, so a limit near the batch size would drop every record.
  /// Checked by [OtlpLogDestination], it is a rule and not a default.
  public long minMaxBufferedBytes() {
    return minMaxBufferedBytes(ProxyEnvironment.current(), batchBytes);
  }

  /// The minimum and default of `maxBufferedBytes` for the given environment and batch size
  static long minMaxBufferedBytes(ProxyEnvironment environment, int batchBytes) {
    return 2L * environment.eventLoopThreads() * batchBytes;
  }

  /// Completed by Lombok, which generates everything but the environment and [#build()]
  public static class Builder {

    /// Set by [OtlpConfig#builder(ProxyEnvironment)], `null` after `toBuilder()`, whose record
    /// has all values
    private ProxyEnvironment environment;

    /// Builds the record with the derived default: an omitted `maxBufferedBytes`, still `0`
    /// here, becomes the minimum for the final `batchBytes`
    public OtlpConfig build() {
      long buffered = maxBufferedBytes;
      if (buffered == 0) {
        buffered =
            minMaxBufferedBytes(
                environment != null ? environment : ProxyEnvironment.current(), batchBytes);
      }
      return new OtlpConfig(
          endpoint,
          headers,
          secretHeaders,
          resourceAttributes,
          timeoutMillis,
          initialRetryDelayMillis,
          maxRetryDelayMillis,
          flushIntervalMillis,
          batchBytes,
          buffered,
          maxConcurrentRequests,
          maximumUnsentLogAgeMillis,
          compression,
          compressionLevel == 0 ? DEFAULT_COMPRESSION_LEVEL : compressionLevel);
    }
  }
}
