package org.sensepitch.edge;

import static org.sensepitch.edge.OtlpEncoding.RESOURCE_ATTRIBUTES;
import static org.sensepitch.edge.OtlpEncoding.stringAttribute;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/// Sends request log records via OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an
/// OpenTelemetry collector.
///
/// The OTLP specifics are the configuration and the payload, see [OtlpBatchPayload]. Buffering, the
/// concurrent requests, retrying with a circuit breaker, the age limit and finishing are generic,
/// see [HttpBatchSender].
///
/// @see <a href="https://opentelemetry.io/docs/specs/otlp/#otlphttp">OTLP/HTTP</a>
/// @author Jens Wilke
public class OtlpLogDestination extends HttpBatchSender<OtlpBatch> {

  /// Default of the `service.name` resource attribute
  public static final String DEFAULT_SERVICE_NAME = "sensepitch-edge";

  /// Name of the instrumentation scope of the records
  public static final String SCOPE = "org.sensepitch.edge";

  private final int sampleHeadersInterval;

  public OtlpLogDestination(String name, OtlpConfig config) {
    this(name, config, new RequestLogMetrics());
  }

  public OtlpLogDestination(String name, OtlpConfig config, RequestLogMetrics metrics) {
    this(name, config, metrics, 0);
  }

  /// @param sampleHeadersInterval see [RequestLogDestinationConfig#sampleHeadersInterval()]
  public OtlpLogDestination(
      String name, OtlpConfig config, RequestLogMetrics metrics, int sampleHeadersInterval) {
    super(
        settings(name, config),
        new OtlpBatchPayload(config.resourceAttributes(), sampleHeadersInterval),
        metrics);
    this.sampleHeadersInterval = sampleHeadersInterval;
  }

  /// Validates the configuration and derives the settings of the sender
  ///
  /// @throws IllegalArgumentException if the configuration is invalid
  static Settings settings(String name, OtlpConfig config) {
    if (config.endpoint() == null || config.endpoint().isBlank()) {
      throw new IllegalArgumentException("Request log destination " + name + ": endpoint missing");
    }
    URI uri = URI.create(config.endpoint());
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": endpoint must be an http or https URL");
    }
    requirePositive(name, "timeoutMillis", config.timeoutMillis());
    requirePositive(name, "flushIntervalMillis", config.flushIntervalMillis());
    if (config.batchBytes() < OtlpConfig.MIN_BATCH_BYTES) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": batchBytes must be at least "
              + OtlpConfig.MIN_BATCH_BYTES);
    }
    long maxBufferedBytes = config.maxBufferedBytes();
    long minMaxBufferedBytes = config.minMaxBufferedBytes();
    if (maxBufferedBytes < minMaxBufferedBytes) {
      // a hint in case the setting was taken as a limit per thread or per batch
      ProxyLogger.get(OtlpLogDestination.class)
          .warn(
              "Request log destination "
                  + name
                  + ": maxBufferedBytes "
                  + maxBufferedBytes
                  + " not respected, using the minimum "
                  + minMaxBufferedBytes
                  + " = 2 × "
                  + ProxyEnvironment.current().eventLoopThreads()
                  + " event loops × "
                  + config.batchBytes()
                  + " batchBytes, the limit of all batches waiting to be sent");
      maxBufferedBytes = minMaxBufferedBytes;
    }
    requirePositive(name, "maxConcurrentRequests", config.maxConcurrentRequests());
    requirePositive(name, "maximumUnsentLogAgeMillis", config.maximumUnsentLogAgeMillis());
    requirePositive(name, "initialRetryDelayMillis", config.initialRetryDelayMillis());
    requirePositive(name, "maxRetryDelayMillis", config.maxRetryDelayMillis());
    if (config.maxRetryDelayMillis() < config.initialRetryDelayMillis()) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": maxRetryDelayMillis must be at least initialRetryDelayMillis");
    }
    String compression = config.compression();
    if (!OtlpConfig.GZIP.equals(compression) && !OtlpConfig.NONE.equals(compression)) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": compression must be "
              + OtlpConfig.GZIP
              + " or "
              + OtlpConfig.NONE
              + ", not "
              + compression);
    }
    int compressionLevel = config.compressionLevel();
    if (compressionLevel < OtlpConfig.MIN_COMPRESSION_LEVEL
        || compressionLevel > OtlpConfig.MAX_COMPRESSION_LEVEL) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": compressionLevel must be between "
              + OtlpConfig.MIN_COMPRESSION_LEVEL
              + " and "
              + OtlpConfig.MAX_COMPRESSION_LEVEL
              + ", not "
              + compressionLevel);
    }
    return new Settings(
        name,
        uri,
        config.headers() != null ? Map.copyOf(config.headers()) : Map.of(),
        Duration.ofMillis(config.timeoutMillis()),
        config.batchBytes(),
        maxBufferedBytes,
        config.maxConcurrentRequests(),
        config.maximumUnsentLogAgeMillis(),
        config.flushIntervalMillis(),
        compression.equals(OtlpConfig.GZIP),
        compressionLevel,
        new Backoff(config.initialRetryDelayMillis(), config.maxRetryDelayMillis()));
  }

  /// Rejects a number that has no meaning at zero or below, for [#settings(String, OtlpConfig)].
  /// The defaults are preset by the builder, so `0` is never a placeholder for a default.
  private static void requirePositive(String name, String key, long value) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": " + key + " must be positive");
    }
  }

  /// Encoded `Resource` message. By default with `service.name`, `service.instance.id` (the host
  /// name) and `service.version` (the edge version, if known); configured attributes override
  /// them.
  static byte[] encodeResource(Map<String, String> configured) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put(OtelLogFields.SERVICE_NAME, DEFAULT_SERVICE_NAME);
    String instance = instanceId();
    if (instance != null) {
      attributes.put(OtelLogFields.SERVICE_INSTANCE_ID, instance);
    }
    String version = OtlpLogDestination.class.getPackage().getImplementationVersion();
    if (version != null) {
      attributes.put(OtelLogFields.SERVICE_VERSION, version);
    }
    if (configured != null) {
      attributes.putAll(configured);
    }
    ProtobufWriter resource = new ProtobufWriter();
    attributes.forEach((key, value) -> stringAttribute(resource, RESOURCE_ATTRIBUTES, key, value));
    return resource.toByteArray();
  }

  /// Identifies this edge instance: the `HOSTNAME` environment variable, which is the pod name in
  /// Kubernetes, else the local host name
  ///
  /// @return the id, or `null` if none can be determined
  static String instanceId() {
    String hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      return null;
    }
  }

  /// See [RequestLogDestinationConfig#sampleHeadersInterval()]
  int sampleHeadersInterval() {
    return sampleHeadersInterval;
  }
}
