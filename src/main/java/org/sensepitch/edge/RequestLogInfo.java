package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;

/**
 * @author Jens Wilke
 */
public interface RequestLogInfo {

  /** Used as a mock when the request was not received or was malformed */
  HttpVersion NIL_VERSION = new HttpVersion("NIL", 0, 0, false);

  /** Used as a mock when the request was not received or was malformed */
  HttpMethod NIL_METHOD = new HttpMethod("NIL");

  Channel channel();

  String requestId();

  /**
   * The http request received from the client with augmented headers as it was sent to upstream. If
   * the request was not received but an error response was generated, this contains a mock request
   * with request method {@link #NIL_METHOD}.
   *
   * @return augmented client request or mock, never null
   */
  HttpRequest request();

  /**
   * Convenience method to retrieve the host from the host request header field. The host header
   * will be sanitized early on. It is never null and only contains known values. If the request
   * host is empty, the return value will be {@link SanitizeHostHandler#MISSING_HOST}. If it is not
   * matching with any host that is serviced {@link SanitizeHostHandler#UNKNOWN_HOST} is returned.
   * If the client request was not received {@link SanitizeHostHandler#NIL_HOST} is returned.
   *
   * @see SanitizeHostHandler
   * @return the requested host or a replacement token, never {@code null}.
   */
  String requestHeaderHost();

  HttpResponse response();

  /**
   * Size, in bytes, of the message body sent. Total bytes sent, including header bytes and
   * framing is {@link #bytesSent()}
   */
  long contentBytes();

  /**
   * Total bytes sent for this request including headers. However, this only accounts
   * for HTTP traffic and does not include HTTPS
   */
  long bytesSent();

  /**
   * Total bytes received for this request including headers.
   */
  long bytesReceived();

  long requestStartTimeMillis();

  /** Non-null if an error happened and the request did not complete successfully. */
  Throwable error();

  /** Trailing headers from LastHttpContent */
  HttpHeaders trailingHeaders();

  /**
   * Time from the connection establishment until request was received completely. For a second
   * request in a keep alive connection this includes waiting time. If the request was never
   * received completely or was erroneous, the end time is when the error response was generated.
   */
  long receiveDurationNanos();

  /**
   * Time between the ingress request was received completely and a significant response was
   * received. Either the response was received completely, or it filled the output buffer, causing
   * throttling until the client received it. This means the response time does not vary in case
   * clients have a slow network. If the response from upstream was never received completely the
   * end time is when the error response was generated.
   */
  long responseTimeNanos();

  /**
   * Total time from starting the request until the response was received completely by the client.
   * This contains slow network on the client side, however, excludes connection handshake and
   * transmission of the request headers.
   */
  long totalDurationNanos();
}
