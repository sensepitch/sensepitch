package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/// Writes the fields of a request into a [RequestLogSink], the single mapping of a request to the
/// request log fields. It is used for both output formats, with a different sink implementation
/// each: [JsonRequestLogger] renders a JSON line, [OtlpRequestLogSink] encodes an OTLP `LogRecord`.
/// So both formats always carry the same fields with the same names.
///
/// The names are defined in [OtelLogFields], e.g. `http.request.method`, `client.address` or
/// `url.path`, as far as the OpenTelemetry semantic conventions have one. Data without a convention
/// is named below `sensepitch.`, see [SensepitchLogFields]. Values that are not present are left
/// out.
///
/// OpenTelemetry has no request id attribute, requests are identified by the trace context, see
/// [TraceContext]. The span id the edge assigned to the request is the request id.
///
/// The mapping does not allocate for the common values: keys and frequent values are constants,
/// the URI is passed as ranges and arrays are streamed into the [RequestLogSink]. Iterating
/// the request headers allocates a small Netty iterator.
///
/// @author Jens Wilke
public final class RequestLogFieldsWriter {

  /// Value of `http.request.method` for a method the conventions do not know
  static final String OTHER_METHOD = "_OTHER";

  /// Methods of RFC 9110 and PATCH, which the semantic conventions know
  static final Set<String> KNOWN_METHODS =
      Set.of("CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE");

  static final String SEC_CH_UA = "sec-ch-ua";
  static final String SEC_CH_UA_PLATFORM = "sec-ch-ua-platform";
  static final String SEC_CH_UA_MOBILE = "sec-ch-ua-mobile";
  static final String SIGNATURE_AGENT = "signature-agent";

  /// Request headers logged as `http.request.header.<name>`, in this order. Client hints and the
  /// language preference identify the browser, the values contain no personal data.
  static final List<String> LOGGED_HEADERS =
      List.of(
          HttpHeaderNames.REFERER.toString(),
          HttpHeaderNames.ACCEPT_LANGUAGE.toString(),
          SEC_CH_UA,
          SEC_CH_UA_PLATFORM,
          SEC_CH_UA_MOBILE,
          SIGNATURE_AGENT);

  /// Request headers left out of the sampled headers, see
  /// [#write(RequestLogInfo, RequestLogSink, int)]: secrets and values that change per request,
  /// and the forwarding headers the edge adds itself, which tell nothing about the client.
  /// `X-Forwarded-For` would also leak the client address past
  /// [RequestLogDestinationConfig#anonymizeClientIp()]. The `Sp-*` headers of the edge are left out
  /// as well, see [SensepitchHeaders#isEdgeHeader]. Case insensitive.
  static final Set<String> SAMPLE_HEADERS_EXCLUDED = sampleHeadersExcluded();

  private static Set<String> sampleHeadersExcluded() {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    names.addAll(
        List.of(
            HttpHeaderNames.COOKIE.toString(),
            HttpHeaderNames.AUTHORIZATION.toString(),
            HttpHeaderNames.PROXY_AUTHORIZATION.toString(),
            TraceContext.TRACEPARENT,
            "X-Forwarded-For",
            "X-Forwarded-Proto"));
    return names;
  }

  /// Keys of [#LOGGED_HEADERS], computed once
  private static final String[] LOGGED_HEADER_KEYS =
      LOGGED_HEADERS.stream().map(OtelLogFields::requestHeader).toArray(String[]::new);

  private static final String CONTENT_TYPE_KEY =
      OtelLogFields.responseHeader(HttpHeaderNames.CONTENT_TYPE.toString());

  private static final String TRACEPARENT_KEY =
      OtelLogFields.requestHeader(TraceContext.TRACEPARENT);

  /// `error.type` of server errors without exception, computed once
  private static final String[] SERVER_ERROR_TYPES = new String[100];

  static {
    for (int i = 0; i < SERVER_ERROR_TYPES.length; i++) {
      SERVER_ERROR_TYPES[i] = Integer.toString(500 + i);
    }
  }

  /// Name and version of the TLS protocols in use today, so they need not be parsed per request
  private static final Map<String, String[]> TLS_PROTOCOLS =
      Map.of(
          "TLSv1.3", new String[] {"tls", "1.3"},
          "TLSv1.2", new String[] {"tls", "1.2"});

  private RequestLogFieldsWriter() {}

  /// Severity of a record, numbers as defined by the OpenTelemetry log data model
  public enum Severity {
    INFO(9),
    WARN(13);

    private final int number;

    Severity(int number) {
      this.number = number;
    }

    public int number() {
      return number;
    }
  }

  /// Emits all fields of the request into the sink, without sampled headers
  public static void write(RequestLogInfo info, RequestLogSink sink) {
    write(info, sink, 0);
  }

  /// Emits all fields of the request into the sink. The order is for reading a JSON line: what was
  /// requested, the result and its cost, who asked, how it was routed, then the details.
  ///
  /// @param sampleHeadersInterval if positive, about one in this many requests, picked at random,
  ///   also gets [SensepitchLogFields#REQUEST_SAMPLE_HEADERS], see
  ///   [RequestLogDestinationConfig#sampleHeadersInterval()]
  public static void write(RequestLogInfo info, RequestLogSink sink, int sampleHeadersInterval) {
    HttpRequest request = info.request();
    HttpResponse response = info.response();
    HttpHeaders headers = request.headers();
    int status = response.status().code();
    sink.record(
        info.requestStartTimeMillis(),
        System.currentTimeMillis(),
        info.error() != null || status >= 500 ? Severity.WARN : Severity.INFO,
        info.traceId(),
        info.spanId(),
        info.traceFlags());
    sink.body(request.method().name(), request.uri(), status);
    writeRequestLine(request, headers, sink);
    sink.number(OtelLogFields.HTTP_RESPONSE_STATUS_CODE, status);
    sink.string(SensepitchLogFields.REQUEST_OUTCOME, info.outcome().value());
    writeError(info, status, sink);
    sink.number(SensepitchLogFields.DURATION_TOTAL_NS, info.totalDurationNanos());
    sink.number(OtelLogFields.HTTP_RESPONSE_BODY_SIZE, info.contentBytes());
    writeClient(info, headers, sink);
    writeRouting(info, sink);
    sink.optionalNumber(OtelLogFields.CLIENT_PORT, info.clientPort());
    sink.optionalNumber(OtelLogFields.SERVER_PORT, info.serverPort());
    writeTls(info, sink);
    writeProtocol(request, sink);
    // single valued by its definition, get does not allocate an iterator
    String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
    if (contentType != null) {
      sink.startArray(CONTENT_TYPE_KEY);
      sink.arrayValue(contentType);
      sink.endArray();
    }
    sink.number(OtelLogFields.HTTP_REQUEST_BODY_SIZE, info.requestBodyBytes());
    sink.number(OtelLogFields.HTTP_REQUEST_SIZE, info.bytesReceived());
    sink.number(OtelLogFields.HTTP_RESPONSE_SIZE, info.bytesSent());
    if (info.clientTraceparent() != null) {
      sink.startArray(TRACEPARENT_KEY);
      sink.arrayValue(info.clientTraceparent());
      sink.endArray();
    }
    writeDetails(info, headers, sink, sampleHeadersInterval);
  }

  /// The client, its user agent and the logged request headers
  private static void writeClient(RequestLogInfo info, HttpHeaders headers, RequestLogSink sink) {
    sink.optionalAddress(OtelLogFields.CLIENT_ADDRESS, info.clientAddress());
    sink.optionalString(
        OtelLogFields.GEO_COUNTRY_ISO_CODE, headers.get(IpTraitsHandler.COUNTRY_HEADER));
    String asn = headers.get(IpTraitsHandler.ASN_HEADER);
    if (asn != null) {
      try {
        sink.number(SensepitchLogFields.CLIENT_ASN, Long.parseLong(asn.trim()));
      } catch (NumberFormatException e) {
        sink.string(SensepitchLogFields.CLIENT_ASN, asn);
      }
    }
    sink.optionalString(OtelLogFields.USER_AGENT_ORIGINAL, headers.get(HttpHeaderNames.USER_AGENT));
    for (int i = 0; i < LOGGED_HEADER_KEYS.length; i++) {
      String name = LOGGED_HEADERS.get(i);
      // contains does not allocate, the value iterator is only created for present headers
      if (headers.contains(name)) {
        sink.startArray(LOGGED_HEADER_KEYS[i]);
        Iterator<? extends CharSequence> values = headers.valueCharSequenceIterator(name);
        while (values.hasNext()) {
          sink.arrayValue(values.next());
        }
        sink.endArray();
      }
    }
  }

  /// Which upstream served the request and how the protection decided
  private static void writeRouting(RequestLogInfo info, RequestLogSink sink) {
    sink.optionalString(OtelLogFields.HTTP_ROUTE, info.route());
    sink.optionalString(SensepitchLogFields.UPSTREAM_NAME, info.upstreamName());
    sink.optionalString(SensepitchLogFields.UPSTREAM_TARGET, info.upstreamTarget());
    sink.optionalNumber(SensepitchLogFields.UPSTREAM_STATUS, info.upstreamStatus());
    sink.optionalString(SensepitchLogFields.PROTECTION_OUTCOME, info.protectionOutcome());
    sink.optionalString(SensepitchLogFields.CONFIG_ID, info.configId());
  }

  /// Method, host, path and query, like the request line of an access log
  private static void writeRequestLine(
      HttpRequest request, HttpHeaders headers, RequestLogSink sink) {
    String method = request.method().name();
    if (KNOWN_METHODS.contains(method)) {
      sink.string(OtelLogFields.HTTP_REQUEST_METHOD, method);
    } else {
      sink.string(OtelLogFields.HTTP_REQUEST_METHOD, OTHER_METHOD);
      sink.string(OtelLogFields.HTTP_REQUEST_METHOD_ORIGINAL, method);
    }
    sink.optionalString(OtelLogFields.SERVER_ADDRESS, headers.get(HttpHeaderNames.HOST));
    String uri = request.uri();
    int query = uri.indexOf('?');
    if (query < 0) {
      sink.string(OtelLogFields.URL_PATH, uri);
    } else {
      sink.string(OtelLogFields.URL_PATH, uri, 0, query);
      // Empty for a bare `?`, so `/a?` stays distinguishable from `/a`, which has no query
      sink.string(OtelLogFields.URL_QUERY, uri, query + 1, uri.length());
    }
  }

  private static void writeProtocol(HttpRequest request, RequestLogSink sink) {
    HttpVersion version = request.protocolVersion();
    if (version == HttpVersion.HTTP_1_1) {
      sink.string(OtelLogFields.NETWORK_PROTOCOL_NAME, "http");
      sink.string(OtelLogFields.NETWORK_PROTOCOL_VERSION, "1.1");
    } else if (version == HttpVersion.HTTP_1_0) {
      sink.string(OtelLogFields.NETWORK_PROTOCOL_NAME, "http");
      sink.string(OtelLogFields.NETWORK_PROTOCOL_VERSION, "1.0");
    } else if (version != RequestLogInfo.NIL_VERSION) {
      sink.string(
          OtelLogFields.NETWORK_PROTOCOL_NAME, version.protocolName().toLowerCase(Locale.ROOT));
      sink.string(
          OtelLogFields.NETWORK_PROTOCOL_VERSION,
          version.majorVersion() + "." + version.minorVersion());
    }
    sink.string(OtelLogFields.NETWORK_TRANSPORT, "tcp");
  }

  /// `TLSv1.3` is reported as protocol name `tls` and version `1.3`
  private static void writeTls(RequestLogInfo info, RequestLogSink sink) {
    String protocol = info.tlsProtocol();
    sink.string(OtelLogFields.URL_SCHEME, protocol != null ? "https" : "http");
    if (protocol == null) {
      return;
    }
    String[] nameAndVersion = TLS_PROTOCOLS.get(protocol);
    if (nameAndVersion == null) {
      nameAndVersion = parseTlsProtocol(protocol);
    }
    sink.optionalString(OtelLogFields.TLS_PROTOCOL_NAME, nameAndVersion[0]);
    sink.optionalString(OtelLogFields.TLS_PROTOCOL_VERSION, nameAndVersion[1]);
    sink.optionalString(OtelLogFields.TLS_CIPHER, info.tlsCipher());
  }

  /// Splits e.g. `SSLv3` into `ssl` and `3`, entries are `null` if absent
  static String[] parseTlsProtocol(String protocol) {
    int digit = 0;
    while (digit < protocol.length() && !Character.isDigit(protocol.charAt(digit))) {
      digit++;
    }
    String name = protocol.substring(0, digit).toLowerCase(Locale.ROOT);
    if (name.endsWith("v")) {
      name = name.substring(0, name.length() - 1);
    }
    return new String[] {
      name.isEmpty() ? null : name, digit < protocol.length() ? protocol.substring(digit) : null
    };
  }

  /// `error.type` is the exception class or, for a server error without exception, the status
  /// code, as the conventions for HTTP servers demand. The exception is described with the
  /// `exception.*` attributes of the conventions for exceptions in logs.
  private static void writeError(RequestLogInfo info, int status, RequestLogSink sink) {
    Throwable error = info.error();
    if (error != null) {
      sink.string(OtelLogFields.ERROR_TYPE, error.getClass().getName());
      sink.string(OtelLogFields.EXCEPTION_TYPE, error.getClass().getName());
      sink.optionalString(OtelLogFields.EXCEPTION_MESSAGE, error.getMessage());
    } else if (status >= 500) {
      sink.string(
          OtelLogFields.ERROR_TYPE,
          status < 600 ? SERVER_ERROR_TYPES[status - 500] : Integer.toString(status));
    }
  }

  /// The remaining Sensepitch fields: connection, fingerprints, header names, partial durations
  private static void writeDetails(
      RequestLogInfo info, HttpHeaders headers, RequestLogSink sink, int sampleHeadersInterval) {
    sink.string(SensepitchLogFields.CONNECTION_ID, info.connectionId());
    sink.number(SensepitchLogFields.CONNECTION_REQUEST_COUNT, info.connectionRequestCount());
    sink.optionalString(SensepitchLogFields.IP_TRAITS, headers.get(IpTraitsHandler.TRAITS_HEADER));
    sink.optionalString(
        SensepitchLogFields.TLS_CLIENT_JA4, headers.get(Ja4HeaderHandler.JA4_HEADER));
    sink.optionalString(
        SensepitchLogFields.ADMISSION_TOKEN, headers.get(Deflector.ADMISSION_TOKEN_HEADER));
    sink.optionalString(SensepitchLogFields.BYPASS, headers.get(BypassCheck.HEADER));
    sink.optionalString(
        SensepitchLogFields.TRAFFIC_TYPE, headers.get(Deflector.TRAFFIC_TYPE_HEADER));
    sink.bool(SensepitchLogFields.REQUEST_COOKIE_PRESENT, headers.contains(HttpHeaderNames.COOKIE));
    sink.startArray(SensepitchLogFields.REQUEST_HEADER_NAMES);
    Iterator<Map.Entry<CharSequence, CharSequence>> entries = headers.iteratorCharSequence();
    while (entries.hasNext()) {
      sink.arrayValue(entries.next().getKey());
    }
    sink.endArray();
    if (sampleHeadersInterval > 0
        && ThreadLocalRandom.current().nextInt(sampleHeadersInterval) == 0) {
      writeSampleHeaders(headers, sink);
    }
    sink.number(SensepitchLogFields.DURATION_RECEIVE_NS, info.receiveDurationNanos());
    sink.number(SensepitchLogFields.DURATION_RESPONSE_NS, info.responseTimeNanos());
  }

  /// All request headers as `Name: value` in the order received, except
  /// [#SAMPLE_HEADERS_EXCLUDED] and the headers of the edge, see [SensepitchHeaders]. Allocates, but only for the sampled requests.
  private static void writeSampleHeaders(HttpHeaders headers, RequestLogSink sink) {
    sink.startArray(SensepitchLogFields.REQUEST_SAMPLE_HEADERS);
    Iterator<Map.Entry<CharSequence, CharSequence>> entries = headers.iteratorCharSequence();
    while (entries.hasNext()) {
      Map.Entry<CharSequence, CharSequence> entry = entries.next();
      String name = entry.getKey().toString();
      if (!SAMPLE_HEADERS_EXCLUDED.contains(name) && !SensepitchHeaders.isEdgeHeader(name)) {
        sink.arrayValue(name + ": " + entry.getValue());
      }
    }
    sink.endArray();
  }
}
