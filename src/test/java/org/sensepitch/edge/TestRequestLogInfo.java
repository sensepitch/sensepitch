package org.sensepitch.edge;

import io.netty.channel.Channel;
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

/// A [RequestLogInfo] for testing request loggers and metrics without a pipeline. Byte counts and
/// durations are fixed values.
///
/// @author Jens Wilke
record TestRequestLogInfo(
    Channel channel, HttpRequest request, HttpResponse response, Throwable error)
    implements RequestLogInfo {

  /// Request for `example.com/` with the given method and response status, no channel
  static TestRequestLogInfo of(HttpMethod method, int status) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    request.headers().set(HttpHeaderNames.HOST, "example.com");
    HttpResponse response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
    return new TestRequestLogInfo(null, request, response, null);
  }

  TestRequestLogInfo withChannel(Channel channel) {
    return new TestRequestLogInfo(channel, request, response, error);
  }

  TestRequestLogInfo withError(Throwable error) {
    return new TestRequestLogInfo(channel, request, response, error);
  }

  @Override
  public String requestId() {
    return "0";
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
