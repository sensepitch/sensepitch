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

  /// Short id of the connection, groups the requests of a keep-alive connection
  public static final String CONNECTION_ID = "sensepitch.connection.id";

  /// Number of requests before this one on the same connection
  public static final String CONNECTION_REQUEST_COUNT = "sensepitch.connection.request_count";

  /// Traits of the client address, e.g. `asn=64496, country=DE, crawler=...`
  public static final String IP_TRAITS = "sensepitch.ip_traits";

  /// Autonomous system number of the client address, OpenTelemetry has no attribute for it
  public static final String CLIENT_ASN = "sensepitch.client.asn";

  /// JA4 TLS fingerprint, the conventions only define JA3
  public static final String TLS_CLIENT_JA4 = "sensepitch.tls.client.ja4";

  /// Admission token of the protection
  public static final String ADMISSION_TOKEN = "sensepitch.admission_token";

  /// Reason the protection was bypassed
  public static final String BYPASS = "sensepitch.bypass";

  /// Whether a cookie header was sent, its content is not logged
  public static final String REQUEST_COOKIE_PRESENT = "sensepitch.request.cookie_present";

  /// Names of the request headers in order, they identify a client without personal data
  public static final String REQUEST_HEADER_NAMES = "sensepitch.request.header_names";

  /// Durations in nanoseconds, OpenTelemetry defines durations as metrics only, see
  /// [RequestLogInfo#receiveDurationNanos()]
  public static final String DURATION_RECEIVE_NS = "sensepitch.duration.receive_ns";

  /// See [RequestLogInfo#responseTimeNanos()]
  public static final String DURATION_RESPONSE_NS = "sensepitch.duration.response_ns";

  /// See [RequestLogInfo#totalDurationNanos()]
  public static final String DURATION_TOTAL_NS = "sensepitch.duration.total_ns";
}
