package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class FlowAndExceptionTest {

  ProxyMetrics proxyMetrics = new ProxyMetrics();

  EmbeddedChannel upstreamChannel;

  EmbeddedChannel ingressChannel =
      EmbeddedChannel.builder()
          .handlers(
              new TimeoutsTest.FakeSslHandler(),
              new RequestLoggingHandler(new StandardOutRequestLogger()),
              new HttpServerKeepAliveHandler(),
              new DownstreamHandler(new MockUpstream(), proxyMetrics),
              new ExceptionHandler(proxyMetrics))
          .build();

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
        EmbeddedChannel.builder().handlers(new ForwardHandler(ingressChannel)).build();
  }

  class MockUpstream implements Upstream {

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      Promise<Channel> promise = downstreamContext.executor().newPromise();
      Channel upstreamChannel = upstreamChannel(downstreamContext.channel());
      promise.setSuccess(upstreamChannel);
      return promise;
    }

    @Override
    public void release(Channel ch) {
      // ignore
    }
  }
}
