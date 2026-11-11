package org.sensepitch.edge;

/// Names of the request log fields defined by OpenTelemetry: the record level fields of the log
/// data model and the attributes of the semantic conventions, checked against version
/// {@value #SEMCONV_VERSION}. They are used by every output format, see
/// [RequestLogFieldsWriter]. Our own fields, for which no convention exists, are in
/// [SensepitchLogFields].
///
/// @see <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/">Attribute
///   registry</a>
/// @author Jens Wilke
public final class OtelLogFields {

  private OtelLogFields() {}

  /// Version of the OpenTelemetry semantic conventions the names follow
  public static final String SEMCONV_VERSION = "1.45.0";

  /// Schema URL of the semantic conventions version, sent with the OTLP export
  public static final String SCHEMA_URL = "https://opentelemetry.io/schemas/" + SEMCONV_VERSION;

  // record level fields of the log data model, top level keys in JSON

  /// Start of the request
  public static final String TIMESTAMP = "timestamp";

  /// Time the record was produced, when the request was completed
  public static final String OBSERVED_TIMESTAMP = "observed_timestamp";

  /// Identifies the kind of record, see [SensepitchLogFields#EVENT_NAME_VALUE]
  public static final String EVENT_NAME = "event.name";

  public static final String SEVERITY_TEXT = "severity_text";
  public static final String SEVERITY_NUMBER = "severity_number";

  /// W3C trace id of the request
  public static final String TRACE_ID = "trace_id";

  /// W3C span id the edge assigned to the request, the request id
  public static final String SPAN_ID = "span_id";

  // attributes of the semantic conventions

  /// Client address, subject to anonymization
  public static final String CLIENT_ADDRESS = "client.address";

  public static final String CLIENT_PORT = "client.port";

  /// Requested host, the sanitized `Host` header
  public static final String SERVER_ADDRESS = "server.address";

  /// Local port the request was received on
  public static final String SERVER_PORT = "server.port";

  /// Request method, `_OTHER` for methods the conventions do not know
  public static final String HTTP_REQUEST_METHOD = "http.request.method";

  /// The request method, if `http.request.method` is `_OTHER`
  public static final String HTTP_REQUEST_METHOD_ORIGINAL = "http.request.method_original";

  /// The matched route, the path pattern of the routing, e.g. `/api/*`
  public static final String HTTP_ROUTE = "http.route";

  public static final String URL_PATH = "url.path";
  public static final String URL_QUERY = "url.query";
  public static final String URL_SCHEME = "url.scheme";
  public static final String NETWORK_PROTOCOL_NAME = "network.protocol.name";
  public static final String NETWORK_PROTOCOL_VERSION = "network.protocol.version";
  public static final String NETWORK_TRANSPORT = "network.transport";
  public static final String TLS_PROTOCOL_NAME = "tls.protocol.name";
  public static final String TLS_PROTOCOL_VERSION = "tls.protocol.version";

  /// Cipher suite, IANA name
  public static final String TLS_CIPHER = "tls.cipher";

  public static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
  public static final String HTTP_REQUEST_BODY_SIZE = "http.request.body.size";
  public static final String HTTP_RESPONSE_BODY_SIZE = "http.response.body.size";

  /// Bytes received for the request, including headers
  public static final String HTTP_REQUEST_SIZE = "http.request.size";

  /// Bytes sent for the response, including headers
  public static final String HTTP_RESPONSE_SIZE = "http.response.size";

  public static final String USER_AGENT_ORIGINAL = "user_agent.original";

  /// Country of the client address, two letters
  public static final String GEO_COUNTRY_ISO_CODE = "geo.country.iso_code";

  /// Exception class, or the status code for a server error without exception
  public static final String ERROR_TYPE = "error.type";

  public static final String EXCEPTION_TYPE = "exception.type";
  public static final String EXCEPTION_MESSAGE = "exception.message";

  /// Prefix of a request header attribute, followed by the lowercase header name, the value is a
  /// string array
  public static final String HTTP_REQUEST_HEADER_PREFIX = "http.request.header.";

  /// Attribute name of a request header
  public static String requestHeader(String headerName) {
    return HTTP_REQUEST_HEADER_PREFIX + headerName;
  }

  /// Prefix of a response header attribute, followed by the lowercase header name, the value is a
  /// string array
  public static final String HTTP_RESPONSE_HEADER_PREFIX = "http.response.header.";

  /// Attribute name of a response header
  public static String responseHeader(String headerName) {
    return HTTP_RESPONSE_HEADER_PREFIX + headerName;
  }

  // resource attributes, describe the producer of the records in the OTLP export

  public static final String SERVICE_NAME = "service.name";

  /// Identifies the edge instance within a fleet
  public static final String SERVICE_INSTANCE_ID = "service.instance.id";

  /// Version of the edge
  public static final String SERVICE_VERSION = "service.version";
}
