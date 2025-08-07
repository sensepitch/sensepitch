package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Receive response from upstream and pass it on downstream
 *
 * @author Jens Wilke
 */
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  static ProxyLogger DEBUG = ProxyLogger.get(ForwardHandler.class);
  private Channel downstream;
  private final ChannelPool pool;
  private boolean closeConnection = false;

  public ForwardHandler(Channel downstream, ChannelPool pool) {
    this.downstream = downstream;
    this.pool = pool;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (downstream == null) {
      DEBUG.error(
          ctx.channel(),
          msg.getClass().getName()
              + " -> downstream is null, getting unexpected data from upstream");
      return;
    }
    if (msg instanceof HttpResponse) {
      HttpResponse response = (HttpResponse) msg;
      String connection = response.headers().get(HttpHeaderNames.CONNECTION);
      closeConnection = connection != null && connection.equalsIgnoreCase("close");
      // if message contains response and content, write below
      if (!(msg instanceof HttpContent)) {
        downstream.write(response);
      }
    }
    if (msg instanceof LastHttpContent) {
      // Channel downstreamCopy = downstream;
      // DownstreamProgress.progress(downstream, "received last content from upstream, flushing
      // response");
      // The write of an empty last content will fail regularly, since the client might have closed
      // the connection already. Maybe introduce error handling for non empty last contents and
      // requests only.
      downstream.writeAndFlush(msg, downstream.voidPromise());
      if (closeConnection) {
        ctx.channel().close();
      }
      if (pool != null) {
        // disconnect from downstream, if upstream sends us more data we don't expect it
        downstream = null;
        // even if closed, we should release it
        pool.release(ctx.channel(), ctx.voidPromise());
      }
    } else if (msg instanceof HttpContent) {
      downstream.write(msg, downstream.voidPromise());
    }
  }

  /** Flush if output buffer is full and apply back pressure to downstream */
  // FIXME: backpressure needs to get implemented for sending to upstream
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    DEBUG.trace(
        ctx.channel(), "channelWritabilityChanged, isWritable=" + ctx.channel().isWritable());
    if (ctx.channel().isWritable()) {
      downstream.setOption(ChannelOption.AUTO_READ, true);
    } else {
      // FIXME: in test we never get the isWritable=true
      // downstream.setOption(ChannelOption.AUTO_READ, false);
      ctx.channel().flush();
    }
  }

  // FIXME: improve error handling, if downstream is connected we should fire exception there
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // DEBUG.trace(downstream, ctx.channel(), "upstream read exception closing downstream");
    DEBUG.upstreamError(ctx.channel(), "downstream=" + DEBUG.channelId(downstream), cause);
    if (downstream != null) {
      downstream.pipeline().fireExceptionCaught(new UpstreamException(cause));
      downstream = null;
    }
    ctx.close();
  }

  static class UpstreamException extends ChannelException {

    public UpstreamException(Throwable cause) {
      super(cause);
    }
  }
}
