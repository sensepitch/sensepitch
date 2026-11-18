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
import io.netty.handler.codec.http.HttpHeaders;
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

/// @author Jens Wilke
public class DownstreamHandler extends ChannelDuplexHandler {

  private static final ProxyLogger LOG = ProxyLogger.get(DownstreamHandler.class);

  private final Upstream upstream;

  /// The upstream connection of the active exchange, `null` when no exchange is active. A listener
  /// compares it with the future it was added to, to tell whether its exchange is still active.
  private Future<Channel> upstreamChannelFuture;

  private Runnable flushTask;

  /// The response headers of the active exchange were written to the client, so an error can no
  /// longer be answered with an error response, see [#completeWithError]
  private boolean responseStarted;

  /// The request of the current exchange was received completely, for the log of an unexpected
  /// close, see [#channelInactive(ChannelHandlerContext)]
  private boolean requestComplete;

  /// A close was requested via the pipeline, so the proxy ends the connection on purpose, see
  /// [#close(ChannelHandlerContext, ChannelPromise)]
  private boolean closeRequested;

  public DownstreamHandler(Upstream upstream, ProxyMetrics metrics) {
    this.upstream = upstream;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpRequest request) {
      // no pipelining, the HttpServerCodec delivers content only between a request and its last
      // content, so a new request while the exchange is active is the only unexpected case
      if (upstreamChannelFuture != null) {
        LOG.debug(ctx.channel(), "another request is unexpected, phase=" + exchangePhase());
        ReferenceCountUtil.release(request);
        completeWithError(
            ctx,
            new HttpResponseStatus(
                HttpResponseStatus.BAD_REQUEST.code(), "another request is unexpected"));
        return;
      }
      RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
      if (requestLog != null) {
        requestLog.setUpstream(upstream);
      }
      requestComplete = request instanceof LastHttpContent;
      responseStarted = false;
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // upstream might complete the response before the client sent the LastHttpContent request
      // e.g. for a get request that has no body
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
        return;
      }
      requestComplete = true;
      // Upstream channel might be still connecting or retrieved and checked by the pool.
      // Queue in all content we receive via the listener.
      upstreamChannelFuture.addListener(
          (FutureListener<Channel>)
              future -> {
                forwardLastContentAndFlush(ctx, future, (LastHttpContent) msg);
              });
    } else if (msg instanceof HttpContent) {
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
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
                    if (!f.isSuccess()) {
                      ctx.executor()
                          .execute(
                              () ->
                                  failExchange(
                                      ctx,
                                      future,
                                      f.cause(),
                                      RequestOutcome.UPSTREAM_WRITE_FAILED,
                                      HttpResponseStatus.valueOf(502, "Upstream write problem")));
                    }
                  });
    } else {
      // upstream future listener is called within ingress event loop
      assert ctx.executor().inEventLoop();
      ReferenceCountUtil.release(msg);
      failExchange(
          ctx,
          future,
          future.cause(),
          RequestOutcome.UPSTREAM_CONNECT_FAILED,
          HttpResponseStatus.valueOf(502, "Upstream connection problem"));
    }
  }

  /// Ends the exchange of `exchangeUpstream` after an upstream failure that is reported
  /// asynchronously. The failure is ignored if the exchange is over already: the response was
  /// written completely, or another error ended it, e.g. a response timeout. Otherwise the report
  /// would change the request log of the next request on a keep-alive connection, or a second
  /// response would be sent.
  void failExchange(
      ChannelHandlerContext ctx,
      Future<Channel> exchangeUpstream,
      Throwable cause,
      RequestOutcome outcome,
      HttpResponseStatus status) {
    if (upstreamChannelFuture != exchangeUpstream) {
      LOG.trace(ctx.channel(), "upstream failure after the exchange ended: " + cause);
      return;
    }
    LOG.debug(ctx.channel(), "upstream failure, outcome=" + outcome + ": " + cause);
    RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
    if (requestLog != null) {
      requestLog.setException(cause);
    }
    RequestLoggingHandler.reportOutcome(ctx, outcome);
    completeWithError(ctx, status);
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

  /// Ends the active exchange after an error and closes the client connection. The upstream
  /// connection is dropped, so it sends nothing more to the client. If the response has not
  /// started, the client gets an error response with `status`. If it has started, the headers and
  /// maybe a part of the body are out, and a second response would corrupt the stream, so the
  /// connection is closed without one: the client sees a truncated response, which it can tell
  /// from a complete one by the content length or the missing last chunk.
  void completeWithError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    if (upstreamChannelFuture != null) {
      closeUpstream(upstreamChannelFuture);
      upstreamChannelFuture = null;
    }
    if (responseStarted) {
      responseStarted = false;
      ctx.channel().close();
      return;
    }
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  /// Closes the upstream connection of an exchange once it is established, also when it is still
  /// connecting, since there is no connection pool to return it to
  private static void closeUpstream(Future<Channel> upstreamChannelFuture) {
    upstreamChannelFuture.addListener(
        (FutureListener<Channel>)
            future -> {
              if (future.isSuccess()) {
                future.resultNow().close();
              }
            });
  }

  /// Send the HTTP request, which may include content, upstream
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
              assert ctx.executor().inEventLoop();
              if (future.isSuccess()) {
                future.resultNow().write(request);
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

  /// Add standard minimal proxy request headers. We don't need to set X-Forwarded-Host, because
  /// this is already set in the Host header, also for https and SNI. We also don't include code
  /// here the support non-standard ports. If additional headers are needed, another handler can be
  /// added depending on configuration.
  private static void addProxyHeaders(ChannelHandlerContext ctx, HttpRequest request) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
      request.headers().set("X-Forwarded-For", addr.getAddress().getHostAddress());
    }
    request.headers().set("X-Forwarded-Proto", "https");
  }

  /// Throttle reading, if the upstream is connected. If buffer is full send flush. If upstream is
  /// null, it means we received the last content, so no more flush is needed.
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture == null || !upstreamChannelFuture.isSuccess()) {
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

  /// If the channel becomes inactive, make sure upstream reads are enabled, so upstream read is
  /// completed and the connection is put back into the pool.
  ///
  /// That should work okay for small responses. For longer responses it might be better to close
  /// the upstream channel to avoid transferring data needlessly.
  ///
  /// An unexpected close is logged once at debug level, it is no fault of ours. Expected is a
  /// close without an exchange in progress (idle keep-alive connection, close after the response,
  /// shutdown drain) or a close the proxy requested via the pipeline: error responses, timeouts and
  /// exceptions, including connection resets, which the [ExceptionHandler] counts. Unexpected is
  /// any other close while an exchange is in progress: the client closes the connection, or the
  /// event loops end after the shutdown drain timed out. The request log records the request as
  /// [RequestOutcome#CLIENT_ABORTED] and `ingressRequestsAborted` counts it, the debug line adds
  /// the phase and the upstream state.
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null) {
      if (!closeRequested) {
        LOG.debug(
            ctx.channel(),
            "unexpected close during the exchange, phase="
                + exchangePhase()
                + ", upstream="
                + upstreamState());
      }
      if (upstreamChannelFuture.isSuccess()) {
        upstreamChannelFuture.resultNow().config().setAutoRead(true);
      }
    }
    super.channelInactive(ctx);
  }

  /// Phase of the exchange for the log of an unexpected close, named like the phases of the
  /// [ExceptionHandler]
  private String exchangePhase() {
    if (!requestComplete) {
      return "upload";
    }
    return responseStarted ? "responding" : "waiting";
  }

  /// State of the upstream connection for the log of an unexpected close
  private String upstreamState() {
    if (!upstreamChannelFuture.isDone()) {
      return "connecting";
    }
    return upstreamChannelFuture.isSuccess() ? "connected" : "connect failed";
  }

  /// Marks the close as requested by the proxy. Every close of ours passes here: a close via the
  /// channel, e.g. `ChannelFutureListener.CLOSE`, starts at the tail of the pipeline. A close by
  /// the peer does not, it only fires [#channelInactive(ChannelHandlerContext)].
  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    closeRequested = true;
    super.close(ctx, promise);
  }

  /// Remove upstream reference when processing for this request is complete. The upstream channel
  /// will go back to the pool, so we need to ensure that we don't have it anymore for throttling.
  /// Throttling can only occur in response to a write, so we are sure that there is no pending
  /// throttling.
  ///
  /// @see #channelWritabilityChanged(ChannelHandlerContext)
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse response) {
      sanitizeResponseHeaders(response.headers());
      responseStarted = true;
    }
    if (msg instanceof LastHttpContent) {
      // there is no connection pool, so the upstream connection is closed after each response,
      // also if the upstream would keep it alive. The reference is cleared here to ensure no more
      // throttling is done.
      if (upstreamChannelFuture != null) {
        closeUpstream(upstreamChannelFuture);
        upstreamChannelFuture = null;
      }
      responseStarted = false;
    }
    super.write(ctx, msg, promise);
  }

  /// Keep upstream connection options out of the client connection. Transfer-Encoding stays so
  /// the downstream HTTP codec can frame the decoded content as chunks.
  private static void sanitizeResponseHeaders(HttpHeaders headers) {
    // Read all Connection fields before removing any: Connection can name itself or another field.
    for (String connection : headers.getAll(HttpHeaderNames.CONNECTION)) {
      for (String option : connection.split(",")) {
        String name = option.trim();
        if (!name.isEmpty()) {
          headers.remove(name);
        }
      }
    }
    headers.remove(HttpHeaderNames.CONNECTION);
    headers.remove(HttpHeaderNames.KEEP_ALIVE);
    headers.remove(HttpHeaderNames.PROXY_CONNECTION);
    headers.remove(HttpHeaderNames.TE);
    headers.remove(HttpHeaderNames.TRAILER);
    headers.remove(HttpHeaderNames.UPGRADE);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isSuccess()) {
      upstreamChannelFuture.resultNow().close();
    }
    super.exceptionCaught(ctx, cause);
  }
}
