package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Handler for upstream communication, receives response from upstream and pass it on downstream
 *
 * @author Jens Wilke
 */
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  static ProxyLogger LOG = ProxyLogger.get(ForwardHandler.class);
  private Channel downstream;

  public ForwardHandler(Channel ingress) {
    this.downstream = ingress;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (downstream != null) {
      LOG.error(
        "upstream closed but request still in flight, downstream=" + downstream.id() +
        ", upstream=" + ctx.channel().id());
    }
    super.channelInactive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (downstream == null) {
      LOG.error(
          ctx.channel(),
          msg.getClass().getName()
              + " -> downstream is null, getting unexpected data from upstream");
      return;
    }
    if (msg instanceof HttpResponse) {
      // if message contains response and content, write below
      if (!(msg instanceof HttpContent)) {
        downstream.write(msg);
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
      downstream = null;
    } else if (msg instanceof HttpContent) {
      downstream.write(msg, downstream.voidPromise());
    }
  }

  /** Flush if output buffer is full and apply back pressure to downstream */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable()) {
      downstream.setOption(ChannelOption.AUTO_READ, true);
    } else {
      downstream.setOption(ChannelOption.AUTO_READ, false);
      flush(ctx);
    }
  }

  /**
   * Flush output to upstream. If we don't stop reading from ingress fast enough it may happen that
   * the output buffer is already filled again when the flush is complete. Issue another flush until
   * writable again.
   */
  void flush(ChannelHandlerContext ctx) {
    ctx.channel()
        .writeAndFlush(Unpooled.EMPTY_BUFFER)
        .addListener(
            future -> {
              if (future.isSuccess()) {
                if (!ctx.channel().isWritable()) {
                  flush(ctx);
                }
              }
            });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // DEBUG.trace(downstream, ctx.channel(), "upstream read exception closing downstream");
    LOG.upstreamError(ctx.channel(), "downstream=" + LOG.channelId(downstream), cause);
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
