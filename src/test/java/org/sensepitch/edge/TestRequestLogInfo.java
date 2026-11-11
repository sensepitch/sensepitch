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
/// durations and the trace context are fixed values. The `with…` methods set a value and return
/// this instance, each test creates its own via [#of(HttpMethod, int)].
///
/// @author Jens Wilke
final class TestRequestLogInfo implements RequestLogInfo {

  static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  static final String SPAN_ID = "00f067aa0ba902b7";

  private final HttpRequest request;
  private final HttpResponse response;
  private InetAddress clientAddress;
  private String tlsProtocol;
  private Throwable error;
  private String upstreamName;
  private String upstreamTarget;
  private RequestOutcome outcome = RequestOutcome.COMPLETED;
  private int upstreamStatus;
  private String route;
  private String configId;
  private String protectionOutcome;

  private TestRequestLogInfo(HttpRequest request, HttpResponse response) {
    this.request = request;
    this.response = response;
  }

  /// Request for `example.com/` with the given method and response status, no client address
  static TestRequestLogInfo of(HttpMethod method, int status) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    request.headers().set(HttpHeaderNames.HOST, "example.com");
    HttpResponse response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
    return new TestRequestLogInfo(request, response);
  }

  TestRequestLogInfo withClientAddress(String ip) {
    try {
      clientAddress = InetAddress.getByName(ip);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
    return this;
  }

  /// With a TLS connection, cipher is always `TLS_AES_128_GCM_SHA256`
  TestRequestLogInfo withTls(String protocol) {
    tlsProtocol = protocol;
    return this;
  }

  TestRequestLogInfo withError(Throwable error) {
    this.error = error;
    return this;
  }

  /// With an upstream that served the request
  TestRequestLogInfo withUpstream(String name, String target) {
    upstreamName = name;
    upstreamTarget = target;
    return this;
  }

  TestRequestLogInfo withOutcome(RequestOutcome outcome, int upstreamStatus) {
    this.outcome = outcome;
    this.upstreamStatus = upstreamStatus;
    return this;
  }

  TestRequestLogInfo withRoute(String route) {
    this.route = route;
    return this;
  }

  TestRequestLogInfo withConfigId(String configId) {
    this.configId = configId;
    return this;
  }

  TestRequestLogInfo withProtectionOutcome(String protectionOutcome) {
    this.protectionOutcome = protectionOutcome;
    return this;
  }

  @Override
  public HttpRequest request() {
    return request;
  }

  @Override
  public HttpResponse response() {
    return response;
  }

  @Override
  public InetAddress clientAddress() {
    return clientAddress;
  }

  @Override
  public String tlsProtocol() {
    return tlsProtocol;
  }

  @Override
  public Throwable error() {
    return error;
  }

  @Override
  public String upstreamName() {
    return upstreamName;
  }

  @Override
  public String upstreamTarget() {
    return upstreamTarget;
  }

  @Override
  public RequestOutcome outcome() {
    return outcome;
  }

  @Override
  public int upstreamStatus() {
    return upstreamStatus;
  }

  @Override
  public String route() {
    return route;
  }

  @Override
  public String configId() {
    return configId;
  }

  @Override
  public String protectionOutcome() {
    return protectionOutcome;
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
