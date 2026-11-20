package org.sensepitch.edge;

import static io.netty.util.concurrent.Ticker.newMockTicker;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.MockTicker;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// @author Jens Wilke
class TimeoutsTest {

  ConnectionConfig cfg =
      ConnectionConfig.builder()
          .readTimeoutSeconds(10)
          .writeTimeoutSeconds(20)
          .responseTimeoutSeconds(30)
          .build();

  ProxyMetrics proxyMetrics = new ProxyMetrics();

  MockTicker ticker = newMockTicker();

  {
    // don't let the timer start at 0, which is never the case
    ticker.advance(123, SECONDS);
  }

  @RegisterExtension LogCapture logs = new LogCapture();

  RequestLogCapture requestLogCapture = new RequestLogCapture();
  RequestLogger requestLog = requestLogCapture.plainText();

  EmbeddedChannel upstreamChannel;

  EmbeddedChannel ingressChannel =
      EmbeddedChannel.builder()
          .ticker(ticker)
          .handlers(
              new FakeSslHandler(),
              new RequestLoggingHandler(proxyMetrics, requestLog),
              new IngressTimeoutHandler(cfg, proxyMetrics),
              new DownstreamHandler(new MockUpstream(), proxyMetrics),
              new ExceptionHandler(proxyMetrics))
          .build();

  @Test
  public void test408ResponseWhenTimeoutBeforeRequestReceived() {
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(9, SECONDS);
    rattle();
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "first_request")).isEqualTo(0);
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "first_request")).isEqualTo(1);
    assertThat(ingressChannel.isActive()).isFalse();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(408);
    assertThat(response.headers().toString()).contains("connection: close");
  }

  /// After a response with keep alive the client has the read timeout to send the next request,
  /// otherwise the connection is closed with 408. This happens for every idle keep-alive
  /// connection.
  @Test
  public void keepAliveIdleTimeout() {
    sendRequest("/empty-length");
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().toString()).contains("keep-alive: timeout=10");
    ticker.advance(9, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isFalse();
    response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(408);
    assertThat(response.headers().toString()).contains("connection: close");
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "keep_alive")).isEqualTo(1);
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "first_request")).isEqualTo(0);
  }

  /// Receiving a part of the request moves the deadline, the timeout is rescheduled instead of
  /// firing when the original deadline passes.
  @Test
  public void receivingExtendsReadTimeout() {
    ticker.advance(5, SECONDS);
    rattle();
    // headers only, the request is not complete, the upstream stays silent
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/no-response"));
    ticker.advance(5, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(4, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isFalse();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(408);
    assertThat(receiveTimeouts(SanitizeHostHandler.MISSING_HOST, "first_request")).isEqualTo(1);
    // the request headers were received, so the request is logged
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.REQUEST_TIMEOUT);
  }

  /// Status 200 but connection will be closed since content size unknown. No keep-alive header is
  /// added. A content type that is not multipart does not delimit the response either.
  @ParameterizedTest
  @ValueSource(strings = {"/unknown-length", "/unknown-length-text"})
  public void test200UnknownContent(String uri) {
    sendRequest(uri);
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().toString()).contains("connection: close");
    assertThat(response.headers().toString()).doesNotContain("keep-alive");
    assertThat(ingressChannel.isActive()).isFalse();
  }

  @Test
  public void test200EmptyContent() {
    sendRequest("/empty-length");
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().toString()).contains("connection: keep-alive");
    assertThat(response.headers().toString()).contains("keep-alive: timeout=10");
    assertThat(ingressChannel.isActive()).isTrue();
    rattle();
    sendRequest("/empty-length");
    response = ingressChannel.readOutbound();
    assertThat(ingressChannel.isActive()).isTrue();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().toString()).contains("connection: keep-alive");
    assertThat(response.headers().toString()).contains("keep-alive: timeout=10");
  }

  @Test
  public void upstreamResponseTimeout() {
    sendRequest("/no-response");
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response).isNull();
    ticker.advance(29, SECONDS);
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response).isNull();
    ticker.advance(1, SECONDS);
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response).isNotNull();
    assertThat(response.status().code()).isEqualTo(504);
    assertThat(ingressChannel.isActive()).isFalse();
    assertThat(requestLogCapture.lines())
        .singleElement()
        .asString()
        .contains("GET /no-response HTTP/1.1 504");
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.UPSTREAM_TIMEOUT);
    assertOutcomeCount(RequestOutcome.UPSTREAM_TIMEOUT, 1);
    assertErrorCount("waiting", "timeout", 1);
    assertErrorCount("waiting", "other", 0);
  }

  /// Our own timeouts are counted as error type `timeout`, apart from unexpected errors
  void assertErrorCount(String phase, String type, long expected) {
    assertThat(
            proxyMetrics
                .ingressErrorCounter
                .labelValues(SanitizeHostHandler.MISSING_HOST, phase, type)
                .getLongValue())
        .isEqualTo(expected);
  }

  /// Read timeouts of the ingress and phase. The ingress is `nil_host` when no request was read,
  /// otherwise `missing_host`, the test requests have no host header.
  long receiveTimeouts(String ingress, String phase) {
    return proxyMetrics.ingressReceiveTimeoutCounter.labelValues(ingress, phase).getLongValue();
  }

  /// The metric counts the outcome of the request log, see [ProxyMetrics#countRequestOutcome]. The
  /// requests have no host header, there is no [SanitizeHostHandler] in the pipeline.
  void assertOutcomeCount(RequestOutcome outcome, long expected) {
    assertThat(
            proxyMetrics
                .requestOutcomeCounter
                .labelValues(SanitizeHostHandler.MISSING_HOST, outcome.value())
                .getLongValue())
        .isEqualTo(expected);
  }

  @Test
  public void upstreamResponseTimeoutSecond() {
    sendRequest("/empty-length");
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response).isNotNull();
    sendRequest("/no-response");
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response).isNull();
    ticker.advance(29, SECONDS);
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response).isNull();
    ticker.advance(1, SECONDS);
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response).isNotNull();
    assertThat(response.status().code()).isEqualTo(504);
    assertThat(ingressChannel.isActive()).isFalse();
  }

  @Test
  public void writeTimeout() {
    sendRequest("/no-response-initially-send-response-later-but-no-content");
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(12, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    ingressChannel.writeAndFlush(response);
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(19, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isFalse();
    ingressChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(ExceptionHandler.class.getName()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(System.Logger.Level.DEBUG);
              assertThat(e.message()).contains("phase=responding");
              assertThat(e.error()).isInstanceOf(IngressTimeoutHandler.WriteTimeoutException.class);
            });
    // logged when the connection closes, the timeout was reported first
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.RESPONSE_WRITE_TIMEOUT);
    assertOutcomeCount(RequestOutcome.RESPONSE_WRITE_TIMEOUT, 1);
    assertErrorCount("responding", "timeout", 1);
    assertErrorCount("responding", "other", 0);
  }

  /// The upstream starts responding before the request body is complete, e.g. with an early 413.
  /// The connection does not stay open for another request, it is closed after the rest of the
  /// body was read, see [IngressTimeoutHandler] for why not at once.
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void earlyResponseClosesConnectionAfterTheBody(boolean chunked) {
    ingressChannel.writeInbound(requestWithBody("/early-response", chunked));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
    assertThat(response.headers().contains(HttpHeaderNames.KEEP_ALIVE)).isFalse();
    ticker.advance(9, SECONDS);
    rattle();
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(BODY)));
    ticker.advance(9, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).as("receiving moves the read timeout").isTrue();
    ingressChannel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(BODY)));
    assertThat(ingressChannel.isActive()).isFalse();
    assertThat((Object) ingressChannel.readOutbound()).isNull();
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "after_response")).isEqualTo(0);
    assertThat(requestLogCapture.lines())
        .singleElement()
        .asString()
        .contains("POST /early-response HTTP/1.1 200");
  }

  /// After an early response the client stops sending the body: the connection is closed after the
  /// read timeout, without a 408, the request was answered already.
  @Test
  public void earlyResponseThenClientStalls() {
    ingressChannel.writeInbound(requestWithBody("/early-response", false));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
    ticker.advance(9, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(ingressChannel.isActive()).isFalse();
    assertThat((Object) ingressChannel.readOutbound()).isNull();
    // the request was logged, so like a keep-alive timeout it counts for the TLS server name
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "after_response")).isEqualTo(1);
    assertThat(receiveTimeouts(SanitizeHostHandler.NIL_HOST, "keep_alive")).isEqualTo(0);
    assertThat(requestLogCapture.outcomes())
        .hasSize(1)
        .doesNotContain(RequestOutcome.REQUEST_TIMEOUT);
  }

  /// A request that does not allow keep-alive gets `Connection: close` and the connection is closed
  /// after the response, the upstream response itself would allow keep-alive.
  @ParameterizedTest
  @ValueSource(strings = {"HTTP/1.1 close", "HTTP/1.0", "HTTP/1.0 close"})
  public void requestWithoutKeepAliveClosesConnection(String versionAndConnection) {
    String[] parts = versionAndConnection.split(" ");
    HttpRequest request =
        new DefaultHttpRequest(HttpVersion.valueOf(parts[0]), HttpMethod.GET, "/empty-length");
    if (parts.length > 1) {
      request.headers().set(HttpHeaderNames.CONNECTION, parts[1]);
    }
    ingressChannel.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
    assertThat(ingressChannel.isActive()).isFalse();
  }

  /// An HTTP/1.0 client asking for keep-alive keeps the connection
  @Test
  public void http10KeepAliveStaysOpen() {
    HttpRequest request =
        new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/empty-length");
    request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
    ingressChannel.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    assertThat(ingressChannel.isActive()).isTrue();
  }

  /// The response delimits its end without a content length, so the connection can stay open
  @ParameterizedTest
  @ValueSource(strings = {"/chunked", "/multipart", "/no-content"})
  public void selfDelimitedResponseKeepsConnection(String uri) {
    sendRequest(uri);
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    assertThat(response.headers().get(HttpHeaderNames.KEEP_ALIVE)).isEqualTo("timeout=10, max=123");
    assertThat(ingressChannel.isActive()).isTrue();
  }

  /// A response written while a request without a body is passed on, before its empty
  /// `LastHttpContent`, is no early response: the client sent everything with the head. This is
  /// what a handler answering on the head does, e.g. a fixed response.
  @Test
  public void responseToBodylessRequestHeadKeepsConnection() {
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/early-response"));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    ingressChannel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    sendRequest("/empty-length");
    rattle();
    response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    assertThat(ingressChannel.isActive()).isTrue();
  }

  /// A `100 Continue` before the body is complete is no early response: it is passed on unchanged
  /// and the final response after the complete request keeps the connection. The test writes the
  /// responses, as [DownstreamHandler] ends the exchange with the `LastHttpContent` of a 1xx.
  @Test
  public void informationalResponseIsNoEarlyResponse() {
    ingressChannel.writeInbound(requestWithBody("/no-response", false));
    rattle();
    ingressChannel.write(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    ingressChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status()).isEqualTo(HttpResponseStatus.CONTINUE);
    assertThat(response.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
    assertThat((Object) ingressChannel.readOutbound()).isInstanceOf(LastHttpContent.class);
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(BODY)));
    rattle();
    ingressChannel.writeAndFlush(emptyLengthResponse());
    response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    assertThat(ingressChannel.isActive()).isTrue();
  }

  /// A 1xx head whose `LastHttpContent` never comes, e.g. the exchange failed in between, does not
  /// hide the final response: it gets the keep-alive decision, here without a length it closes
  /// the connection.
  @Test
  public void finalResponseAfterUnfinishedInformationalResponse() {
    sendRequest("/no-response");
    rattle();
    ingressChannel.writeAndFlush(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    ingressChannel.writeAndFlush(
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY));
    assertThat(((HttpResponse) ingressChannel.readOutbound()).status())
        .isEqualTo(HttpResponseStatus.CONTINUE);
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
    assertThat(ingressChannel.isActive()).isFalse();
  }

  /// On shutdown a connection that is still receiving a request body is not idle: the request is
  /// answered, with `Connection: close`, then the connection is closed.
  @Test
  public void shutdownWhileReceivingBodyClosesAfterResponse() {
    ingressChannel.writeInbound(requestWithBody("/empty-length", false));
    ingressChannel
        .pipeline()
        .fireUserEventTriggered(TrackIngressConnectionsHandler.ShutdownEvent.SHUTDOWN);
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(BODY)));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
    assertThat(ingressChannel.isActive()).isFalse();
  }

  /// The shutdown starts after the response headers were sent with keep-alive, the connection is
  /// closed after the response nevertheless.
  @Test
  public void shutdownDuringResponseClosesAfterIt() {
    sendRequest("/no-response");
    rattle();
    ingressChannel.writeAndFlush(
        new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, emptyLengthResponse().headers().copy()));
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("keep-alive");
    ingressChannel
        .pipeline()
        .fireUserEventTriggered(TrackIngressConnectionsHandler.ShutdownEvent.SHUTDOWN);
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    rattle();
    assertThat(ingressChannel.isActive()).isFalse();
  }

  /// An idle keep-alive connection is closed at once on shutdown
  @Test
  public void shutdownClosesIdleConnection() {
    sendRequest("/empty-length");
    rattle();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel
        .pipeline()
        .fireUserEventTriggered(TrackIngressConnectionsHandler.ShutdownEvent.SHUTDOWN);
    assertThat(ingressChannel.isActive()).isFalse();
  }

  private static final byte[] BODY = {1};

  /// A request whose body of [#BODY] the test sends separately
  private static HttpRequest requestWithBody(String uri, boolean chunked) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
    if (chunked) {
      HttpUtil.setTransferEncodingChunked(request, true);
    } else {
      HttpUtil.setContentLength(request, BODY.length);
    }
    return request;
  }

  private static FullHttpResponse emptyLengthResponse() {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    HttpUtil.setContentLength(response, 0);
    return response;
  }

  private void sendRequest(String uri) {
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
    if (ingressChannel.isActive()) {
      ingressChannel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    }
  }

  /// If upstream is connected it might put tasks in the into ingress again
  private void rattle() {
    if (upstreamChannel != null) {
      while (ingressChannel.hasPendingTasks() || upstreamChannel.hasPendingTasks()) {
        ingressChannel.runPendingTasks();
        upstreamChannel.runPendingTasks();
      }
    } else {
      ingressChannel.runPendingTasks();
    }
  }

  Channel upstreamChannel(Channel ingressChannel) {
    return upstreamChannel =
        EmbeddedChannel.builder()
            .ticker(ticker)
            .handlers(
                new ChannelOutboundHandlerAdapter() {
                  String uri;

                  /// Answers when the request is complete, like a real upstream, or at once for
                  /// an early response. The URI selects the response.
                  @Override
                  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                      throws Exception {
                    if (msg instanceof HttpRequest request) {
                      uri = request.uri();
                      if (uri.contains("early")) {
                        respond(ingressChannel);
                      }
                    }
                    if (msg instanceof LastHttpContent
                        && !uri.contains("no-response")
                        && !uri.contains("early")) {
                      respond(ingressChannel);
                    }
                    super.write(ctx, msg, promise);
                  }

                  void respond(Channel ingressChannel) {
                    FullHttpResponse response =
                        new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            uri.contains("no-content")
                                ? HttpResponseStatus.NO_CONTENT
                                : HttpResponseStatus.OK);
                    if (uri.contains("empty-length") || uri.contains("early")) {
                      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                    }
                    if (uri.contains("chunked")) {
                      HttpUtil.setTransferEncodingChunked(response, true);
                    }
                    if (uri.contains("text")) {
                      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                    }
                    if (uri.contains("multipart")) {
                      response
                          .headers()
                          .set(HttpHeaderNames.CONTENT_TYPE, "multipart/byteranges; boundary=b");
                    }
                    ingressChannel.writeAndFlush(response);
                  }
                })
            .build();
  }

  static class FakeSslHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      ctx.executor()
          .execute(
              () -> {
                ctx.fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
              });
      super.channelActive(ctx);
    }
  }

  class MockUpstream implements Upstream {

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      Promise<Channel> promise = downstreamContext.executor().newPromise();
      Channel upstreamChannel = upstreamChannel(downstreamContext.channel());
      promise.setSuccess(upstreamChannel);
      return promise;
    }
  }
}
