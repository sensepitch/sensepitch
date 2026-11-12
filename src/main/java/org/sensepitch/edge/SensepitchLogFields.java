package org.sensepitch.edge;

/// Names of the request log fields Sensepitch adds, below `sensepitch.`, for data the
/// OpenTelemetry semantic conventions have no attribute for. The standard names are in
/// [OtelLogFields].
///
/// @author Jens Wilke
public final class SensepitchLogFields {

  private SensepitchLogFields() {}

  /// Value of [OtelLogFields#EVENT_NAME] identifying a request log record
  public static final String EVENT_NAME_VALUE = "sensepitch.request";

  /// Why the request ended, see [RequestOutcome]
  public static final String REQUEST_OUTCOME = "sensepitch.request.outcome";

  /// Status of the upstream response when the edge replaced it with a fallback page
  public static final String UPSTREAM_STATUS = "sensepitch.upstream.status";

  /// Short hash of the configuration active when the request started, the same on every node
  /// with the same configuration and edge version
  public static final String CONFIG_ID = "sensepitch.config.id";

  /// Outcome of the deflector protection, the same values as the label of
  /// `sensepitch_deflector_requests_total`
  public static final String PROTECTION_OUTCOME = "sensepitch.protection.outcome";

  /// Short id of the connection, groups the requests of a keep-alive connection
  public static final String CONNECTION_ID = "sensepitch.connection.id";

  /// Number of requests before this one on the same connection
  public static final String CONNECTION_REQUEST_COUNT = "sensepitch.connection.request_count";

  /// Name of the upstream selected for the request in the configuration, e.g.
  /// `upstreams.backend` or `sites.example.com.paths./api/*.upstream`, or of the static response,
  /// e.g. `sites.example.com.response`. Absent when the edge answered itself. OpenTelemetry has no
  /// attribute for the upstream of a proxy.
  public static final String UPSTREAM_NAME = "sensepitch.upstream.name";

  /// Configured target of the selected upstream, `host:port`
  public static final String UPSTREAM_TARGET = "sensepitch.upstream.target";

  /// Traits of the client address, e.g. `asn=64496, country=DE, crawler=...`
  public static final String IP_TRAITS = "sensepitch.ip_traits";

  /// Autonomous system number of the client address, OpenTelemetry has no attribute for it
  public static final String CLIENT_ASN = "sensepitch.client.asn";

  /// JA4 TLS fingerprint, the conventions only define JA3
  public static final String TLS_CLIENT_JA4 = "sensepitch.tls.client.ja4";

  /// Admission token of the protection: the token of a valid admission cookie, or the token issued
  /// for an accepted challenge answer
  public static final String ADMISSION_TOKEN = "sensepitch.protection.admission_token";

  /// Reason the protection was bypassed, set by [BypassCheck#setBypassReason], e.g.
  /// [BypassCheck#REASON_CONFIG_URI_MATCH]
  public static final String BYPASS = "sensepitch.protection.bypass";

  /// Whether the request is human or bot traffic, see [Deflector#TRAFFIC_TYPE_HEADER]
  public static final String TRAFFIC_TYPE = "sensepitch.traffic.type";

  /// Whether a cookie header was sent, its content is not logged
  public static final String REQUEST_COOKIE_PRESENT = "sensepitch.request.cookie_present";

  /// Names of the request headers in order, they identify a client without personal data
  public static final String REQUEST_HEADER_NAMES = "sensepitch.request.header_names";

  /// Request headers of a sample of requests, `Name: value` in the order received, for studying
  /// client fingerprints. Secrets and headers the edge adds are left out, see
  /// [RequestLogDestinationConfig#sampleHeadersInterval()]
  public static final String REQUEST_SAMPLE_HEADERS = "sensepitch.request.sample_headers";

  /// Durations in nanoseconds, OpenTelemetry defines durations as metrics only, see
  /// [RequestLogInfo#receiveDurationNanos()]
  public static final String DURATION_RECEIVE_NS = "sensepitch.duration.receive_ns";

  /// See [RequestLogInfo#responseTimeNanos()]
  public static final String DURATION_RESPONSE_NS = "sensepitch.duration.response_ns";

  /// See [RequestLogInfo#totalDurationNanos()]
  public static final String DURATION_TOTAL_NS = "sensepitch.duration.total_ns";
}
