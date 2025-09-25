package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import java.net.InetSocketAddress;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelDuplexHandler {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  // private final UpstreamRouter upstreamRouter;
  private final Upstream upstream;
  private Future<Channel> upstreamChannelFuture;
  private boolean returnUpstreamToPool;
  private Runnable flushTask;

  /** This avoids that we sent stray content to upstream when not expected */
  private boolean ingressRequestComplete;

  public DownstreamHandler(Upstream upstream, ProxyMetrics metrics) {
    this.upstream = upstream;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpRequest request) {
      if (upstreamChannelFuture != null || ingressRequestComplete) {
        DEBUG.error(ctx.channel(), "another request is unexpected");
        completeWithError(
            ctx,
            new HttpResponseStatus(
                HttpResponseStatus.BAD_REQUEST.code(), "another request is unexpected"));
        return;
      }
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // upstream might complete the response before the client sent the LastHttpContent request
      // e.g. for a get request that has no body
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
        return;
      }
      if (ingressRequestComplete) {
        DEBUG.error(ctx.channel(), "another request is unexpected");
        completeWithError(
            ctx,
            new HttpResponseStatus(
                HttpResponseStatus.BAD_REQUEST.code(), "another request is unexpected"));
        return;
      }
      // Upstream channel might be still connecting or retrieved and checked by the pool.
      // Queue in all content we receive via the listener.
      upstreamChannelFuture.addListener(
          (FutureListener<Channel>)
              future -> forwardLastContentAndFlush(ctx, future, (LastHttpContent) msg));
    } else if (msg instanceof HttpContent) {
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
        return;
      }
      if (ingressRequestComplete) {
        DEBUG.error(ctx.channel(), "another request is unexpected");
        completeWithError(
            ctx,
            new HttpResponseStatus(
                HttpResponseStatus.BAD_REQUEST.code(), "another request is unexpected"));
        return;
      }
      upstreamChannelFuture.addListener(
          (FutureListener<Channel>) future -> forwardContent(future, (HttpContent) msg));
    }
  }

  // runs in another tread!
  void forwardLastContentAndFlush(
      ChannelHandlerContext ctx, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      future
          .resultNow()
          .writeAndFlush(msg)
          .addListener(
              (ChannelFutureListener)
                  f -> {
                    DEBUG.info(
                        ctx.channel().id()
                            + ">"
                            + future.resultNow().id()
                            + ", flushed success="
                            + f.isSuccess()
                            + ", cause="
                            + f.cause());
                    // TODO: error counter!
                    if (!f.isSuccess()) {
                      ctx.executor()
                          .execute(
                              () -> {
                                ctx.pipeline()
                                    .get(RequestLoggingHandler.class)
                                    .setException(f.cause());
                                completeWithError(
                                    ctx, HttpResponseStatus.valueOf(502, "Upstream write problem"));
                              });
                    }
                  });
    } else {
      // upstream future listener is called within ingress event loop
      assert ctx.executor().inEventLoop();
      ReferenceCountUtil.release(msg);
      Throwable cause = future.cause();
      // TODO: counter!
      if (cause instanceof IllegalStateException
          && cause.getMessage() != null
          && cause.getMessage().contains("Too many outstanding acquire operations")) {
        completeWithError(ctx, HttpResponseStatus.valueOf(509, "Bandwidth Limit Exceeded"));
        return;
      }
      DEBUG.error(ctx.channel(), "unknown upstream connection problem", future.cause());
      ctx.pipeline().get(RequestLoggingHandler.class).setException(cause);
      completeWithError(
          ctx, HttpResponseStatus.valueOf(502, "Upstream connection problem"));
    }
  }

  // runs in another tread!
  void forwardContent(Future<Channel> future, HttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().write(msg);
    } else {
      ReferenceCountUtil.release(msg);
      // ignore, only react to an upstream connection problem after receiving LastHttpContent
    }
  }

  void completeWithError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    // TODO: maybe response was already sent
    // TODO: drop upstream
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  /** Send the HTTP request, which may include content, upstream */
  void augmentHeadersAndForwardRequest(ChannelHandlerContext ctx, HttpRequest request) {
    boolean contentExpected =
        (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request))
            && !(request instanceof FullHttpRequest);
    if (contentExpected) {
      // turn off reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
    }
    addProxyHeaders(ctx, request);
    upstreamChannelFuture.addListener(
        (FutureListener<Channel>)
            future -> {
              if (future.isSuccess()) {
                upstreamChannelFuture.resultNow().write(request);
                if (contentExpected) {
                  ctx.channel().config().setAutoRead(true);
                }
              } else {
                ReferenceCountUtil.release(request);
                // ignore, only react to an upstream connection problem after receiving
                // LastHttpContent
              }
            });
  }

  /**
   * Add standard minimal proxy request headers. We don't need to set X-Forwarded-Host, because this
   * is already set in the Host header, also for https and SNI. We also don't include code here the
   * support non-standard ports. If additional headers are needed, another handler can be added
   * depending on configuration.
   *
   * @see SniToHostHeader
   */
  private static void addProxyHeaders(ChannelHandlerContext ctx, HttpRequest request) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
      request.headers().set("X-Forwarded-For", addr.getAddress().getHostAddress());
    }
    request.headers().set("X-Forwarded-Proto", "https");
  }

  /**
   * Throttle reading, if the upstream is connected. If buffer is full send flush. If upstream is
   * null, it means we received the last content, so no more flush is needed.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture == null || !upstreamChannelFuture.isDone()) {
      return;
    }
    if (ctx.channel().isWritable()) {
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
      return;
    }
    upstreamChannelFuture.resultNow().config().setAutoRead(false);
    if (flushTask == null) {
      flushTask =
          () ->
              ctx.channel()
                  .writeAndFlush(Unpooled.EMPTY_BUFFER)
                  .addListener(
                      (ChannelFutureListener)
                          future -> {
                            if (future.isSuccess() && ctx.channel().isActive()) {
                              if (!ctx.channel().isWritable()) {
                                ctx.executor().execute(flushTask);
                              }
                            }
                          });
    }
    ctx.executor().execute(flushTask);
  }

  /**
   * If the channel becomes inactive, make sure upstream reads are enabled, so upstream read is
   * completed and the connection is put back into the pool.
   *
   * <p>That should work okay for small responses. For longer responses it might be better to close
   * the upstream channel to avoid transferring data needlessly.
   *
   * <p>TODO: track and log if the close was unexpected
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
    }
  }

  /**
   * Remove upstream reference when processing for this request is complete. The upstream channel
   * will go back to the pool, so we need to ensure that we don't have it anymore for throttling.
   * Throttling can only occur in response to a write, so we are sure that there is no pending
   * throttling.
   *
   * @see #channelWritabilityChanged(ChannelHandlerContext)
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    // FIXME: sanitize headers
    if (msg instanceof HttpResponse response) {
      // NGINX will send Connection: close after 100 requests
      returnUpstreamToPool = HttpUtil.isKeepAlive(response);
      // DEBUG.trace(ctx.channel(), upstreamChannelFuture.resultNow(),
      //  "connection=" + response.headers().get(HttpHeaderNames.CONNECTION) +
      //  ", keepAlive=" + response.headers().get(HttpHeaderNames.KEEP_ALIVE) +
      //  ", returnUpstreamToPool=" + returnUpstreamToPool);
      response.headers().remove(HttpHeaderNames.KEEP_ALIVE);
      response.headers().remove(HttpHeaderNames.CONNECTION);
    }
    if (msg instanceof LastHttpContent) {
      // we can release upstream channel to pool only as soon as we cleared out
      // the reference here to ensure no more throttling is done
      if (returnUpstreamToPool) {
        upstream.release(upstreamChannelFuture.resultNow());
      } else {
        upstreamChannelFuture.resultNow().close();
      }
      upstreamChannelFuture = null;
    }
    super.write(ctx, msg, promise);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      upstreamChannelFuture.resultNow().close();
    }
    super.exceptionCaught(ctx, cause);
  }

  // TODO: discuss @Sharable
  @Override
  public boolean isSharable() {
    return super.isSharable();
  }
}
