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

  static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
  static final long MAX_RETRY_DELAY_MILLIS = 30_000;

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
    this(
        name,
        config,
        metrics,
        INITIAL_RETRY_DELAY_MILLIS,
        MAX_RETRY_DELAY_MILLIS,
        sampleHeadersInterval);
  }

  /// With other delays, for tests
  OtlpLogDestination(
      String name,
      OtlpConfig config,
      RequestLogMetrics metrics,
      long initialRetryDelayMillis,
      long maxRetryDelayMillis) {
    this(name, config, metrics, initialRetryDelayMillis, maxRetryDelayMillis, 0);
  }

  private OtlpLogDestination(
      String name,
      OtlpConfig config,
      RequestLogMetrics metrics,
      long initialRetryDelayMillis,
      long maxRetryDelayMillis,
      int sampleHeadersInterval) {
    super(
        settings(name, config, initialRetryDelayMillis, maxRetryDelayMillis),
        new OtlpBatchPayload(config.resourceAttributes(), sampleHeadersInterval),
        metrics);
    this.sampleHeadersInterval = sampleHeadersInterval;
  }

  /// Validates the configuration and derives the settings of the sender
  ///
  /// @throws IllegalArgumentException if the configuration is invalid
  static Settings settings(
      String name, OtlpConfig config, long initialRetryDelayMillis, long maxRetryDelayMillis) {
    if (config.endpoint() == null || config.endpoint().isBlank()) {
      throw new IllegalArgumentException("Request log destination " + name + ": endpoint missing");
    }
    URI uri = URI.create(config.endpoint());
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": endpoint must be an http or https URL");
    }
    if (config.batchBytes() != 0 && config.batchBytes() < OtlpConfig.MIN_BATCH_BYTES) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": batchBytes must be at least "
              + OtlpConfig.MIN_BATCH_BYTES);
    }
    if (config.maxBufferedBytes() > 0 && config.maxBufferedBytes() < config.minMaxBufferedBytes()) {
      // a hint in case the setting was taken as a limit per thread or per batch
      ProxyLogger.get(OtlpLogDestination.class)
          .warn(
              "Request log destination "
                  + name
                  + ": maxBufferedBytes "
                  + config.maxBufferedBytes()
                  + " not respected, using the minimum "
                  + config.minMaxBufferedBytes()
                  + " = 2 × "
                  + OtlpConfig.defaultEventLoopThreads()
                  + " event loops × "
                  + config.effectiveBatchBytes()
                  + " batchBytes, the limit of all batches waiting to be sent");
    }
    if (config.maxConcurrentRequests() < 0) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": maxConcurrentRequests must be positive");
    }
    if (config.maximumUnsentLogAgeMillis() < 0) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": maximumUnsentLogAgeMillis must be positive");
    }
    String compression = config.effectiveCompression();
    if (!compression.equals(OtlpConfig.GZIP) && !compression.equals(OtlpConfig.NONE)) {
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
    return new Settings(
        name,
        uri,
        config.headers() != null ? Map.copyOf(config.headers()) : Map.of(),
        Duration.ofMillis(config.effectiveTimeoutMillis()),
        config.effectiveBatchBytes(),
        config.effectiveMaxBufferedBytes(),
        config.effectiveMaxConcurrentRequests(),
        config.effectiveMaximumUnsentLogAgeMillis(),
        config.effectiveFlushIntervalMillis(),
        compression.equals(OtlpConfig.GZIP),
        new Backoff(initialRetryDelayMillis, maxRetryDelayMillis));
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
