package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DecoderException;
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
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelDuplexHandler {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  // private final UpstreamRouter upstreamRouter;
  private final Upstream upstream;
  private final ProxyMetrics metrics;
  private Future<Channel> upstreamChannelFuture;
  private boolean requestReceived;
  private boolean lastContentReceivedFromClient;
  private boolean sslHandshakeComplete;
  private Runnable flushTask;
  private HttpRequest request;

  public DownstreamHandler(Upstream upstream, ProxyMetrics metrics) {
    this.upstream = upstream;
    this.metrics = metrics;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    DEBUG.traceChannelRead(ctx, msg);
    if (msg instanceof HttpRequest request) {
      if (upstreamChannelFuture != null) {
        completeWithError(ctx, new HttpResponseStatus(HttpResponseStatus.BAD_GATEWAY.code(), "pipelining not supported"));
        return;
      }
      requestReceived = true;
      DownstreamProgress.progress(ctx.channel(), "request received, selecting upstream");
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // upstream might complete the response before the client sent the complete request
      // e.g. in an error situation
      if (upstreamChannelFuture != null) {
        DownstreamProgress.progress(ctx.channel(), "last content, waiting for upstream");
        // Upstream channel might be still connecting or retrieved and checked by the pool.
        // Queue in all content we receive via the listener.
        upstreamChannelFuture.addListener((FutureListener<Channel>)
          future -> forwardLastContentAndFlush(ctx, future, (LastHttpContent) msg));
        lastContentReceivedFromClient = true;
      }
    } else if (msg instanceof HttpContent) {
      upstreamChannelFuture.addListener((FutureListener<Channel>)
        future -> forwardContent(future, (HttpContent) msg));
    }
  }

  // runs in another tread!
  void forwardLastContentAndFlush(ChannelHandlerContext ctx, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      String remoteIp = ProxyUtil.extractRemoteIp(ctx);
      String upstreamString = DEBUG.channelId(future.resultNow());
      String requestString = "upstream=" + upstreamString + ", " + request.headers().get(HttpHeaderNames.HOST) + " " + remoteIp + " " + request.method() + " " + request.uri();
      DownstreamProgress.progress(ctx.channel(), "write and flushing last content to upstream, " + requestString);
      future.resultNow().writeAndFlush(msg).addListener((ChannelFutureListener) future1 -> {
        // TODO: counter!
        if (!future1.isSuccess()) {
          completeWithError(ctx, HttpResponseStatus.valueOf(502, "Upstream write problem: " + future1.cause()));
        }
      });
    } else {
      DownstreamProgress.complete(ctx.channel());
      ReferenceCountUtil.release(msg);
      Throwable cause = future.cause();
      // TODO: counter!
      if (cause instanceof IllegalStateException) {
        if (cause.getMessage() != null && cause.getMessage().contains("Too many outstanding acquire operations")) {
          completeWithError(ctx, HttpResponseStatus.valueOf(509, "Bandwidth Limit Exceeded"));
          return;
        }
      }
      DEBUG.error(ctx.channel(), "unknown upstream connection problem", future.cause());
      if (cause.getMessage() != null) {
        completeWithError(ctx, HttpResponseStatus.valueOf(502, "Upstream connection problem: " + cause));
      } else {
        completeWithError(ctx, HttpResponseStatus.valueOf(502, "Upstream connection problem"));
      }
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
    assert sslHandshakeComplete;
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    DownstreamProgress.complete(ctx.channel());
  }

  /**
   * Send the HTTP request, which may include content, upstream
   */
  void augmentHeadersAndForwardRequest(ChannelHandlerContext ctx, HttpRequest request) {
    boolean contentExpected = (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request)) && !(request instanceof FullHttpRequest);
    if (contentExpected) {
      // turn of reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
    }
    addProxyHeaders(ctx, request);
    DEBUG.trace(ctx.channel(), "connecting to upstream contentExpected=" + contentExpected);
    upstreamChannelFuture.addListener((FutureListener<Channel>)
      future -> {
        if (future.isSuccess()) {
          upstreamChannelFuture.resultNow().write(request);
          if (contentExpected) {
            ctx.channel().config().setAutoRead(true);
          }
        } else {
          ReferenceCountUtil.release(request);
          // ignore, only react to an upstream connection problem after receiving LastHttpContent
        }
      }
    );
  }

  /**
   * Add standard minimal proxy request headers. We don't need to set X-Forwarded-Host, because
   * this is already set in the Host header, also for https and SNI. We also don't include
   * code here the support non-standard ports. If additional headers are needed, another
   * handler can be added depending on configuration.
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
   * If upstream is active sending content, throttle reading. In any case flush the buffer it its full.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      if (ctx.channel().isWritable()) {
        DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=true, start upstream reads");
        upstreamChannelFuture.resultNow().config().setAutoRead(true);
      } else {
        DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=false, queuing flush, stop upstream reads");
        upstreamChannelFuture.resultNow().config().setAutoRead(false);
      }
    }
    if (!ctx.channel().isWritable()) {
      DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=false, queuing flush, stop upstream reads");
      // flush task is only created once for the context
      if (flushTask == null) {
        flushTask = new Runnable() {
          @Override
          public void run() {
            ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess() && ctx.channel().isActive()) {
                  if (!ctx.channel().isWritable()) {
                    DEBUG.trace(future.channel(), "flush complete, output buffer still full, queuing another flush");
                    ctx.executor().execute(flushTask);
                  } else {
                    DEBUG.trace(future.channel(), "flush complete, output buffer writable");
                  }
                }
              }
            });
          }
        };
      }
      ctx.executor().execute(flushTask);
    }
  }

  /**
   * If the channel becomes inactive, make sure upstream reads are enabled, so
   * upstream read is completed and the connection is put back into the pool.
   *
   * That should work okay for small responses. For longer responses it might
   * be better to close the upstream channel to avoid transferring data needlessly.
   *
   * TODO: track and log if the close was unexpected
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
    }
    DownstreamProgress.inactive(ctx.channel());
  }

  /**
   * Remove upstream reference when processing for this request is complete.
   * The upstream channel will go back to the pool, so we need to ensure that we don't
   * have it anymore for throttling. Throttling can only occur in response to a write,
   * so we are sure that there is no pending throttling.
   * 
   * @see #channelWritabilityChanged(ChannelHandlerContext)
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof HttpResponse response) {
      response.headers().remove(HttpHeaderNames.KEEP_ALIVE);
      response.headers().remove(HttpHeaderNames.CONNECTION);
    }
    // FIXME: sanitize headers
    if (msg instanceof LastHttpContent) {
      upstreamChannelFuture = null;
      promise = promise.unvoid().addListener((ChannelFutureListener) future -> {
        DownstreamProgress.complete(ctx.channel());
      });
    }
    super.write(ctx, msg, promise);
  }



  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    if (event instanceof SslHandshakeCompletionEvent sslEvent) {
      if (sslEvent.isSuccess()) {
        sslHandshakeComplete = true;
      }
    }
    super.userEventTriggered(ctx, event);
  }

  /**
   * Exception reading from the socket, like a connection reset.
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    // connection reset might include the IP address, not good
    boolean connectionReset = cause instanceof SocketException &&
      cause.getMessage() != null &&
      cause.getMessage().startsWith("Connection reset");
    Throwable decoderException = null;
    if (cause instanceof DecoderException) {
      decoderException = cause.getCause();
    }
    // Extract all exception strings from error log:
    // journalctl -u NAME -n 5000 | grep ERROR | awk 'match($0, /[^ ]+Exception.*/){ print substr($0, RSTART ) }' | sort | uniq
    //
    // Commonly seen:
    // io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an SSL/TLS record
    // io.netty.handler.codec.DecoderException: io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException: error:100000b8:SSL routines:OPENSSL_internal:NO_SHARED_CIPHER
    // io.netty.handler.codec.DecoderException: io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException: error:100000f0:SSL routines:OPENSSL_internal:UNSUPPORTED_PROTOCOL
    // io.netty.handler.codec.DecoderException: io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException: error:100003f2:SSL routines:OPENSSL_internal:SSLV3_ALERT_UNEXPECTED_MESSAGE
    // java.net.SocketException: Connection reset
    // java.net.SocketException: Connection reset 112.254.156.186 java.net.SocketException: Connection reset
    // java.net.SocketException: Connection reset 2053:c0:3700:6157:a256:3692:31aa:1235 java.net.SocketException: Connection reset
    if (!sslHandshakeComplete) {
      // io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException is subtype of SSLHandshakeException
      // maybe switch to catch all SSLException
      if (decoderException instanceof SSLHandshakeException || decoderException instanceof NotSslRecordException) {
        metrics.ingressConnectionErrorSslHandshake.inc();
      } else if (connectionReset) {
          metrics.ingressConnectionResetDuringHandshake.inc();
      } else {
        metrics.ingressOtherHandshakeError.inc();
        DEBUG.downstreamError(ctx.channel(), "handshake error", cause);
      }
      completeAndClose(ctx);
    } else if (!requestReceived || !lastContentReceivedFromClient) {
      if (connectionReset) {
        if (!requestReceived) {
          metrics.ingressConnectionErrorRequestReceiveConnectionReset.inc();
        } else {
          metrics.ingressConnectionErrorContentReceiveConnectionReset.inc();
        }
        completeAndClose(ctx);
      } else {
        if (!requestReceived) {
          metrics.ingressConnectionErrorRequestReceiveOther.inc();
        } else {
          metrics.ingressConnectionErrorContentReceiveOther.inc();
        }
        HttpResponseStatus status = new HttpResponseStatus(502, cause.toString());
        // log always
        DEBUG.downstreamError(ctx.channel(), "request error, requestReceived=" + requestReceived + ", status: " + status, cause);
        completeWithError(ctx, status);
      }
    } else {
      if (connectionReset) {
        metrics.ingressConnectionErrorRespondingConnectionReset.inc();
        completeAndClose(ctx);
      } else {
        metrics.ingressConnectionErrorRespondingOther.inc();
        // this can be a connection reset while waiting or sending the response
        DEBUG.downstreamError(ctx.channel(), "error sending response to ingress", cause);
        if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
          upstreamChannelFuture.resultNow().close();
        }
        completeAndClose(ctx);
      }
    }
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    super.handlerRemoved(ctx);
  }

  // TODO: discuss @Sharable
  @Override
  public boolean isSharable() {
    return super.isSharable();
  }

  private static void completeAndClose(ChannelHandlerContext ctx) {
    DownstreamProgress.complete(ctx.channel());
    ctx.channel().close();
  }

}
