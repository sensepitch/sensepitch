package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;

/// Data of one completed request, passed to a [RequestLogger].
///
/// The interface exposes only what the request log needs, not the Netty channel, so request
/// loggers stay independent of the transport and a wrapper can alter single values, see
/// [AnonymizedClientRequestLogInfo].
///
/// @author Jens Wilke
public interface RequestLogInfo {

  /// Used as a mock when the request was not received or was malformed
  HttpVersion NIL_VERSION = new HttpVersion("NIL", 0, 0, false);

  /// Used as a mock when the request was not received or was malformed
  HttpMethod NIL_METHOD = new HttpMethod("NIL");

  /// Address of the client, `null` if the connection has no IP remote address, e.g. in tests.
  InetAddress clientAddress();

  /// Port of the client, `0` if unknown
  int clientPort();

  /// Local port the request was received on, `0` if unknown
  int serverPort();

  /// Name of the upstream selected for the request, see [Upstream#name()]
  ///
  /// @return the name, or `null` if no upstream was selected, e.g. the edge answered itself
  String upstreamName();

  /// Target of the upstream selected for the request, see [Upstream#target()]
  ///
  /// @return `host:port`, or `null` if no upstream was selected or it has no target
  String upstreamTarget();

  /// Negotiated TLS protocol as reported by the JDK, e.g. `TLSv1.3`, or `null` without TLS
  String tlsProtocol();

  /// Negotiated TLS cipher suite, IANA name, e.g. `TLS_AES_128_GCM_SHA256`, or `null` without TLS
  String tlsCipher();

  /// Short id of the connection, unique within this proxy instance
  String connectionId();

  /// Number of requests before this one on the same connection, `0` for the first request
  int connectionRequestCount();

  /// Id of the request in the form `connection/count`, used by the plain text format
  default String requestId() {
    return connectionId() + "/" + connectionRequestCount();
  }

  /// W3C trace id of the request, 32 lowercase hex characters, or `null` if not assigned
  ///
  /// @see TraceContext
  String traceId();

  /// W3C span id the edge assigned to the request, 16 lowercase hex characters, or `null` if not
  /// assigned. This is the standard request id, it is sent to upstream in the `traceparent` header.
  String spanId();

  /// W3C trace flags of the request, e.g. `01` for sampled, or `null` if not assigned
  String traceFlags();

  /// The `traceparent` header as sent by the client, or `null` if absent
  String clientTraceparent();

  /// The http request received from the client with augmented headers as it was sent to upstream.
  /// If the request was not received but an error response was generated, this contains a mock
  /// request with request method [#NIL_METHOD].
  ///
  /// @return augmented client request or mock, never null
  HttpRequest request();

  /// Convenience method to retrieve the host from the host request header field. The host header
  /// will be sanitized early on. It is never null and only contains known values. If the request
  /// host is empty, the return value will be [SanitizeHostHandler#MISSING_HOST]. If it is not
  /// matching with any host that is serviced [SanitizeHostHandler#UNKNOWN_HOST] is returned. If the
  /// client request was not received [SanitizeHostHandler#NIL_HOST] is returned.
  ///
  /// @see SanitizeHostHandler
  /// @return the requested host or a replacement token, never `null`.
  String requestHeaderHost();

  HttpResponse response();

  /// Size, in bytes, of the message body sent. Total bytes sent, including header bytes and framing
  /// is [#bytesSent()]
  long contentBytes();

  /// Total bytes sent for this request including headers. However, this only accounts for HTTP
  /// traffic and does not include HTTPS
  long bytesSent();

  /// Total bytes received for this request including headers.
  long bytesReceived();

  /// Size, in bytes, of the request body received from the client
  long requestBodyBytes();

  long requestStartTimeMillis();

  /// Non-null if an error happened and the request did not complete successfully.
  Throwable error();

  /// Trailing headers from LastHttpContent
  HttpHeaders trailingHeaders();

  /// Time from the connection establishment until request was received completely. For a second
  /// request in a keep alive connection this includes waiting time. If the request was never
  /// received completely or was erroneous, the end time is when the error response was generated.
  long receiveDurationNanos();

  /// Time between the ingress request was received completely and a significant response was
  /// received. Either the response was received completely, or it filled the output buffer, causing
  /// throttling until the client received it. This means the response time does not vary in case
  /// clients have a slow network. If the response from upstream was never received completely the
  /// end time is when the error response was generated.
  long responseTimeNanos();

  /// Total time from starting the request until the response was received completely by the client.
  /// This contains slow network on the client side, however, excludes connection handshake and
  /// transmission of the request headers.
  long totalDurationNanos();
}
