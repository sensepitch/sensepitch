package org.sensepitch.edge;

import static org.sensepitch.edge.ProxyMetrics.UpstreamErrorType.CLOSED_IN_FLIGHT;
import static org.sensepitch.edge.ProxyMetrics.UpstreamErrorType.EXCEPTION_WITHOUT_REQUEST;
import static org.sensepitch.edge.ProxyMetrics.UpstreamErrorType.UNEXPECTED_DATA;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/// Handler for upstream communication, receives response from upstream and passes it downstream.
///
/// An informational response (1xx) is passed on together with its `LastHttpContent`, but does not
/// end the exchange: the upstream stays attached to the downstream for the final response.
///
/// An exception during an exchange is passed to the downstream pipeline as [UpstreamException],
/// where [ExceptionHandler] counts it and logs its first occurrence at warning, so here it is
/// logged at debug only, with the upstream channel. The other events of a misbehaving upstream are
/// counted in [ProxyMetrics#upstreamErrorCounter] and logged at debug, see [ProxyLogger] for the
/// levels: a close before the response is complete (the request then ends by its timeout or abort
/// outcome), data after the response, and an exception after the response. Their first
/// occurrences are logged at warning, see [ProxyLogger#incrementAndWarnOnce].
///
/// @author Jens Wilke
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  private static final ProxyLogger LOG = ProxyLogger.get(ForwardHandler.class);

  private final ProxyMetrics metrics;
  private final String upstream;
  private Channel downstream;
  private boolean pausedDownstreamReads;

  /// An informational response was forwarded, its `LastHttpContent` follows, then the final
  /// response. The upstream stays attached to the downstream until that final response ends.
  private boolean informationalResponse;

  /// @param upstream name of the upstream, the label of [ProxyMetrics#upstreamErrorCounter]
  public ForwardHandler(Channel ingress, ProxyMetrics metrics, String upstream) {
    this.downstream = ingress;
    this.metrics = metrics;
    this.upstream = upstream;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (downstream != null) {
      LOG.incrementAndWarnOnce(
          metrics.upstreamErrorCounter,
          new String[] {upstream, CLOSED_IN_FLIGHT.label},
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
      LOG.incrementAndWarnOnce(
          metrics.upstreamErrorCounter,
          new String[] {upstream, UNEXPECTED_DATA.label},
          ctx.channel(),
          msg.getClass().getName()
              + " -> downstream is null, getting unexpected data from upstream");
      ReferenceCountUtil.release(msg);
      return;
    }
    if (msg instanceof HttpResponse) {
      informationalResponse =
          ((HttpResponse) msg).status().codeClass() == HttpStatusClass.INFORMATIONAL;
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
      boolean informational = informationalResponse;
      informationalResponse = false;
      if (!informational) {
        resumeDownstreamReads();
        downstream = null;
      }
    } else if (msg instanceof HttpContent) {
      downstream.write(msg, downstream.voidPromise());
    }
  }

  /// Flush if output buffer is full and apply back pressure to downstream: pause the client reads
  /// if they are on, and resume only a pause imposed here, when the upstream is writable again or
  /// at the end of the response, see [#resumeDownstreamReads()]. Today nothing else switches the
  /// client reads off while the upstream is unwritable: the pause of [DownstreamHandler] during the
  /// upstream connect ends before the request is flushed. The guard keeps a future second reason
  /// to pause, e.g. rate limiting, intact.
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (downstream == null) {
      return;
    }
    if (ctx.channel().isWritable()) {
      resumeDownstreamReads();
    } else {
      if (downstream.config().isAutoRead()) {
        pausedDownstreamReads = true;
        downstream.setOption(ChannelOption.AUTO_READ, false);
      }
      flush(ctx);
    }
  }

  /// Release this exchange's backpressure when the upstream catches up or finishes responding.
  private void resumeDownstreamReads() {
    if (pausedDownstreamReads) {
      pausedDownstreamReads = false;
      downstream.setOption(ChannelOption.AUTO_READ, true);
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
    String message = "upstream failure, downstream=" + LOG.channelId(downstream) + ": " + cause;
    if (downstream != null) {
      LOG.debug(ctx.channel(), message, null);
      // cleared first: the exception handling closes the upstream, which is no close in flight
      Channel exchangeDownstream = downstream;
      downstream = null;
      exchangeDownstream.pipeline().fireExceptionCaught(new UpstreamException(cause));
    } else {
      LOG.incrementAndWarnOnce(
          metrics.upstreamErrorCounter,
          new String[] {upstream, EXCEPTION_WITHOUT_REQUEST.label},
          ctx.channel(),
          message);
    }
    ctx.close();
  }

  static class UpstreamException extends ChannelException {

    public UpstreamException(Throwable cause) {
      super(cause);
    }
  }
}
