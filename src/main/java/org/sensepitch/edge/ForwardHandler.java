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
import io.netty.util.ReferenceCountUtil;

/// Handler for upstream communication, receives response from upstream and passes it downstream.
///
/// An exception during an exchange is passed to the downstream pipeline as [UpstreamException],
/// where [ExceptionHandler] counts it. The other events of a misbehaving upstream are counted in
/// [ProxyMetrics#upstreamErrorCounter] and logged at debug, see [ProxyLogger] for the levels: a
/// close before the response is complete (the request then ends by its timeout or abort outcome),
/// data after the response, and an exception after the response.
///
/// @author Jens Wilke
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  private static final ProxyLogger LOG = ProxyLogger.get(ForwardHandler.class);
  private final ProxyMetrics metrics;
  private final String upstream;
  private Channel downstream;

  /// @param upstream name of the upstream, the label of [ProxyMetrics#upstreamErrorCounter]
  public ForwardHandler(Channel ingress, ProxyMetrics metrics, String upstream) {
    this.downstream = ingress;
    this.metrics = metrics;
    this.upstream = upstream;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (downstream != null) {
      metrics.countUpstreamError(upstream, "closed_in_flight");
      LOG.debug(
          ctx.channel(),
          "upstream closed but request still in flight, downstream="
              + downstream.id()
              + ", upstream="
              + ctx.channel().id());
    }
    super.channelInactive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (downstream == null) {
      metrics.countUpstreamError(upstream, "unexpected_data");
      LOG.debug(
          ctx.channel(),
          msg.getClass().getName()
              + " -> downstream is null, getting unexpected data from upstream");
      ReferenceCountUtil.release(msg);
      return;
    }
    if (msg instanceof HttpResponse) {
      // if message contains response and content, write below
      if (!(msg instanceof HttpContent)) {
        downstream.write(msg);
      }
    }
    if (msg instanceof LastHttpContent) {
      // The write of an empty last content will fail regularly, since the client might have closed
      // the connection already. Maybe introduce error handling for non empty last contents and
      // requests only.
      downstream.writeAndFlush(msg, downstream.voidPromise());
      downstream = null;
    } else if (msg instanceof HttpContent) {
      downstream.write(msg, downstream.voidPromise());
    }
  }

  /// Flush if output buffer is full and apply back pressure to downstream
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable()) {
      downstream.setOption(ChannelOption.AUTO_READ, true);
    } else {
      downstream.setOption(ChannelOption.AUTO_READ, false);
      flush(ctx);
    }
  }

  /// Flush output to upstream. If we don't stop reading from ingress fast enough it may happen that
  /// the output buffer is already filled again when the flush is complete. Issue another flush
  /// until writable again.
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
    LOG.debug(
        ctx.channel(), "upstream failure, downstream=" + LOG.channelId(downstream) + ": " + cause);
    if (downstream != null) {
      downstream.pipeline().fireExceptionCaught(new UpstreamException(cause));
      downstream = null;
    } else {
      metrics.countUpstreamError(upstream, "exception_without_request");
    }
    ctx.close();
  }

  static class UpstreamException extends ChannelException {

    public UpstreamException(Throwable cause) {
      super(cause);
    }
  }
}
