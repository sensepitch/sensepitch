package org.sensepitch.edge;

import static io.netty.util.concurrent.Ticker.newMockTicker;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.MockTicker;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
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

  EmbeddedChannel upstreamChannel;

  EmbeddedChannel ingressChannel =
      EmbeddedChannel.builder()
          .ticker(ticker)
          .handlers(
              new FakeSslHandler(),
              new RequestLoggingHandler(new StandardOutRequestLogger()),
              new ClientTimeoutHandler(cfg, proxyMetrics),
              new HttpServerKeepAliveHandler(),
              new DownstreamHandler(new MockUpstream(), proxyMetrics))
          .build();

  @Test
  public void test408ResponseWhenTimeoutBeforeRequestReceived() {
    assertThat(ingressChannel.isActive()).isTrue();
    ticker.advance(9, SECONDS);
    rattle();
    assertThat(proxyMetrics.ingressReceiveTimeoutFirstRequest.getLongValue()).isEqualTo(0);
    ticker.advance(1, SECONDS);
    rattle();
    assertThat(proxyMetrics.ingressReceiveTimeoutFirstRequest.getLongValue()).isEqualTo(1);
    assertThat(ingressChannel.isActive()).isFalse();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(408);
    assertThat(response.headers().toString()).contains("connection: close");
  }

  /**
   * Status 200 but connection will be closed since content size unknown. No keep-alive header is
   * added.
   */
  @Test
  public void test200UnknownContent() {
    sendRequest("/unknown-length");
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
  }

  private void sendRequest(String uri) {
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
    if (ingressChannel.isActive()) {
      ingressChannel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    }
  }

  /** If upstream is connected it might put tasks in the into ingress again */
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
                  @Override
                  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                      throws Exception {
                    if (msg instanceof HttpRequest reqeust) {
                      if (!reqeust.uri().contains("no-response")) {
                        FullHttpResponse response =
                            new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                        if (reqeust.uri().contains("empty-length")) {
                          response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                        }
                        ingressChannel.writeAndFlush(response);
                      }
                    }
                    super.write(ctx, msg, promise);
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
