package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class FlowAndExceptionTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics proxyMetrics = new ProxyMetrics();

  /// When set, [MockUpstream] fails to connect with this cause
  Throwable connectFailure;

  /// When set, every write to the upstream channel fails
  boolean failUpstreamWrites;

  /// Upstream channels handed back for reuse

  RequestLogCapture requestLogCapture = new RequestLogCapture();
  RequestLogger requestLog = requestLogCapture.plainText();

  EmbeddedChannel upstreamChannel;

  EmbeddedChannel ingressChannel = ingressChannel(null);

  /// @param remote remote address of the client, or `null` for the default of [EmbeddedChannel]
  EmbeddedChannel ingressChannel(SocketAddress remote) {
    ChannelHandler[] handlers = {
      new TimeoutsTest.FakeSslHandler(),
      new RequestLoggingHandler(new ProxyMetrics(), requestLog),
      new HttpServerKeepAliveHandler(),
      new DownstreamHandler(new MockUpstream(), proxyMetrics),
      new ExceptionHandler(proxyMetrics)
    };
    if (remote == null) {
      return new EmbeddedChannel(handlers);
    }
    return new EmbeddedChannel(handlers) {
      @Override
      protected SocketAddress remoteAddress0() {
        return remote;
      }
    };
  }

  // ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
  @Test
  public void messageIsBufferedInUpstreamAndWrittenAfterFlush() {
    assertThat(upstreamChannel).isNull();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    assertThat(upstreamChannel).isNotNull();
    var obj = upstreamChannel.readOutbound();
    assertThat(obj).isNull();
    upstreamChannel.flush();
    obj = upstreamChannel.readOutbound();
    assertThat(obj).isNotNull();
  }

  @Test
  public void upstreamChannelIsFlushed() {
    assertThat(upstreamChannel).isNull();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    upstreamChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[1000])));
    assertThat(upstreamChannel.isWritable())
        .isTrue()
        .describedAs("Channel writable, buffer not full");
    assertThat(ingressChannel.config().getOption(ChannelOption.AUTO_READ))
        .isTrue()
        .describedAs("Ingress is reading");
    assertThat((Object) upstreamChannel.readOutbound()).isNull();
    ;
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[1000])));
    assertThat((Object) upstreamChannel.readOutbound()).isNotNull();
    assertThat(upstreamChannel.isWritable())
        .isTrue()
        .describedAs("Channel writable, buffer not full");
    assertThat(ingressChannel.config().getOption(ChannelOption.AUTO_READ))
        .isTrue()
        .describedAs("Ingress is reading");
  }

  @Test
  public void upstreamConnectionReset() {
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    ingressChannel.writeInbound(new DefaultLastHttpContent());
    upstreamChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
  }

  /// The response travels from upstream via [ForwardHandler] to the client, hop-by-hop headers are
  /// not forwarded. There is no connection pool, so the upstream connection is closed after the
  /// response, also if the upstream offers keep alive. The client connection stays open.
  @Test
  public void responseIsForwardedAndUpstreamClosed() {
    sendGetRequest();
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=5");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 3);
    HttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1, 2, 3}));
    upstreamChannel.writeInbound(response, content, new DefaultLastHttpContent());
    rattle();
    assertThat((Object) ingressChannel.readOutbound()).isSameAs(response);
    assertThat(response.headers().contains(HttpHeaderNames.KEEP_ALIVE)).isFalse();
    HttpContent received = ingressChannel.readOutbound();
    assertThat(received.content().readableBytes()).isEqualTo(3);
    received.release();
    assertThat((Object) ingressChannel.readOutbound()).isInstanceOf(LastHttpContent.class);
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(ingressChannel.isOpen()).isTrue();
  }

  /// Upstream sends `Connection: close`, the upstream connection is closed like after every
  /// response.
  @Test
  public void upstreamConnectionCloseIsClosed() {
    sendGetRequest();
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    upstreamChannel.writeInbound(response, new DefaultLastHttpContent());
    rattle();
    assertThat(upstreamChannel.isOpen()).isFalse();
    ingressChannel.finishAndReleaseAll();
  }

  /// Data arriving after the response is complete has nowhere to go and must be released. The
  /// upstream connection is closed after the response, so this only happens when the extra bytes
  /// are decoded within the same read as the end of the response.
  @Test
  public void unexpectedDataFromUpstreamIsDropped() {
    sendGetRequest();
    HttpContent stray = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
        new DefaultLastHttpContent(),
        stray);
    rattle();
    ingressChannel.finishAndReleaseAll();
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(stray.refCnt()).isZero();
    assertThat(logs.messages(ForwardHandler.class))
        .anySatisfy(m -> assertThat(m).contains("downstream is null"));
  }

  @Test
  public void proxyHeadersAreAdded() {
    ingressChannel = ingressChannel(new InetSocketAddress("192.0.2.7", 4711));
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().set("X-Forwarded-For", "203.0.113.1");
    ingressChannel.writeInbound(request);
    upstreamChannel.flush();
    HttpRequest forwarded = upstreamChannel.readOutbound();
    assertThat(forwarded.headers().getAll("X-Forwarded-For")).containsExactly("192.0.2.7");
    assertThat(forwarded.headers().get("X-Forwarded-Proto")).isEqualTo("https");
  }

  /// No pipelining, a second request before the response is complete is refused.
  @Test
  public void secondRequestIsRejected() {
    sendGetRequest();
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/2"));
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(400);
    assertThat(ingressChannel.isOpen()).isFalse();
  }

  /// Body content without a request has no upstream to go to.
  @Test
  public void contentWithoutRequestIsReleased() {
    HttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    LastHttpContent last = new DefaultLastHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    ingressChannel.writeInbound(content, last);
    assertThat(content.refCnt()).isZero();
    assertThat(last.refCnt()).isZero();
    assertThat(upstreamChannel).isNull();
  }

  @Test
  public void upstreamConnectFailureIs502() {
    connectFailure = new SocketException("Connection refused");
    HttpContent body = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"),
        body,
        new DefaultLastHttpContent());
    rattle();
    assertThat(body.refCnt()).isZero();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.lines()).anySatisfy(l -> assertThat(l).contains("502"));
  }

  @Test
  public void upstreamWriteFailureIs502() {
    failUpstreamWrites = true;
    sendGetRequest();
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(response.status().reasonPhrase()).isEqualTo("Upstream write problem");
    assertThat(ingressChannel.isOpen()).isFalse();
  }

  /// An exception on the upstream connection while waiting for the response ends the ingress
  /// request with a 502 and closes the upstream.
  @Test
  public void upstreamExceptionWhileWaitingIs502() {
    sendGetRequest();
    upstreamChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(upstreamChannel.isOpen()).isFalse();
  }

  /// A slow client fills the ingress output buffer. Upstream reading is paused and the buffer is
  /// flushed until it drains.
  @Test
  public void slowClientPausesUpstreamAndFlushes() {
    sendGetRequest();
    ingressChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(10, 20));
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
        new DefaultHttpContent(Unpooled.copiedBuffer(new byte[100])));
    rattle();
    // the empty buffers are the flushes DownstreamHandler issues while not writable, reading from
    // upstream was paused meanwhile and is resumed after the buffer drained
    assertThat(ingressChannel.outboundMessages())
        .map(o -> o.getClass().getSimpleName())
        .containsExactly(
            "DefaultHttpResponse", "EmptyByteBuf", "DefaultHttpContent", "EmptyByteBuf");
    assertThat(ingressChannel.isWritable()).isTrue();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
    ingressChannel.finishAndReleaseAll();
  }

  /// When the client goes away, upstream reading continues so the response can be drained.
  @Test
  public void ingressInactiveResumesUpstreamReading() {
    sendGetRequest();
    upstreamChannel.config().setAutoRead(false);
    ingressChannel.close();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
  }

  private void sendGetRequest() {
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        new DefaultLastHttpContent());
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
    ChannelHandler forward = new ForwardHandler(ingressChannel);
    if (failUpstreamWrites) {
      ChannelHandler failWrites =
          new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
              ReferenceCountUtil.release(msg);
              promise.setFailure(new SocketException("Broken pipe"));
            }
          };
      return upstreamChannel = new EmbeddedChannel(failWrites, forward);
    }
    return upstreamChannel = new EmbeddedChannel(forward);
  }

  class MockUpstream implements Upstream {

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      Promise<Channel> promise = downstreamContext.executor().newPromise();
      if (connectFailure != null) {
        promise.setFailure(connectFailure);
        return promise;
      }
      Channel upstreamChannel = upstreamChannel(downstreamContext.channel());
      promise.setSuccess(upstreamChannel);
      return promise;
    }
  }
}
