package org.sensepitch.edge;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.UnknownHostException;

/// A [RequestLogInfo] for testing request loggers and metrics without a pipeline. Byte counts,
/// durations and the trace context are fixed values.
///
/// @author Jens Wilke
record TestRequestLogInfo(
    InetAddress clientAddress,
    String tlsProtocol,
    HttpRequest request,
    HttpResponse response,
    Throwable error,
    String upstreamName,
    String upstreamTarget)
    implements RequestLogInfo {

  static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  static final String SPAN_ID = "00f067aa0ba902b7";

  /// Request for `example.com/` with the given method and response status, no client address
  static TestRequestLogInfo of(HttpMethod method, int status) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    request.headers().set(HttpHeaderNames.HOST, "example.com");
    HttpResponse response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
    return new TestRequestLogInfo(null, null, request, response, null, null, null);
  }

  TestRequestLogInfo withClientAddress(String ip) {
    try {
      return new TestRequestLogInfo(
          InetAddress.getByName(ip),
          tlsProtocol,
          request,
          response,
          error,
          upstreamName,
          upstreamTarget);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /// With a TLS connection, cipher is always `TLS_AES_128_GCM_SHA256`
  TestRequestLogInfo withTls(String protocol) {
    return new TestRequestLogInfo(
        clientAddress, protocol, request, response, error, upstreamName, upstreamTarget);
  }

  TestRequestLogInfo withError(Throwable error) {
    return new TestRequestLogInfo(
        clientAddress, tlsProtocol, request, response, error, upstreamName, upstreamTarget);
  }

  /// With an upstream that served the request
  TestRequestLogInfo withUpstream(String name, String target) {
    return new TestRequestLogInfo(
        clientAddress, tlsProtocol, request, response, error, name, target);
  }

  @Override
  public int clientPort() {
    return clientAddress != null ? 4711 : 0;
  }

  @Override
  public int serverPort() {
    return 443;
  }

  @Override
  public String tlsCipher() {
    return tlsProtocol != null ? "TLS_AES_128_GCM_SHA256" : null;
  }

  @Override
  public String connectionId() {
    return "0";
  }

  @Override
  public int connectionRequestCount() {
    return 0;
  }

  @Override
  public String requestId() {
    return "0";
  }

  @Override
  public String traceId() {
    return TRACE_ID;
  }

  @Override
  public String spanId() {
    return SPAN_ID;
  }

  @Override
  public String traceFlags() {
    return "01";
  }

  @Override
  public String clientTraceparent() {
    return request.headers().get(TraceContext.TRACEPARENT);
  }

  @Override
  public String requestHeaderHost() {
    return request.headers().get(HttpHeaderNames.HOST);
  }

  @Override
  public long contentBytes() {
    return 3;
  }

  @Override
  public long bytesSent() {
    return 100;
  }

  @Override
  public long bytesReceived() {
    return 50;
  }

  @Override
  public long requestBodyBytes() {
    return 7;
  }

  @Override
  public long requestStartTimeMillis() {
    return 0;
  }

  @Override
  public HttpHeaders trailingHeaders() {
    return new DefaultHttpHeaders();
  }

  @Override
  public long receiveDurationNanos() {
    return 1_000;
  }

  @Override
  public long responseTimeNanos() {
    return 2_000;
  }

  @Override
  public long totalDurationNanos() {
    return 3_000;
  }
}
