package org.sensepitch.edge;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Watches the request and response pass through and implement timeouts for the request, response
/// and write. There is only one timeout active at a point in time. The response timeout is actually
/// a timeout for our upstream. Once the upstream starts responding the handler switches to write
/// timeout. The write timeout triggers when no write complete any more, which covers upstream and
/// receiver stalls.
///
/// This must be placed between the http codec handler and the keep alive handler.
///
/// @author Jens Wilke
public final class IngressTimeoutHandler extends ChannelDuplexHandler {

  private static final ProxyLogger LOG = ProxyLogger.get(IngressTimeoutHandler.class);

  private final ConnectionConfig config;
  private boolean responding = false;
  private TimeoutTask task;
  private final ProxyMetrics proxyMetrics;
  private boolean keepAlive;

  /// No request in progress: before the first request, and after a response on a keep-alive
  /// connection
  private boolean idle = true;

  /// The proxy shuts down, the connection closes after the current response
  private boolean closing;

  public IngressTimeoutHandler(ConnectionConfig config, ProxyMetrics proxyMetrics) {
    this.config = config;
    this.proxyMetrics = proxyMetrics;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.handlerAdded(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.channelActive(ctx);
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.channelRegistered(ctx);
  }

  private void initialize(ChannelHandlerContext ctx) throws Exception {
    if (task != null) {
      return;
    }
    task =
        new TimeoutTask(ctx, config.readTimeoutSeconds()) {
          @Override
          public void fire() {
            proxyMetrics.ingressReceiveTimeoutFirstRequest.inc();
            RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.REQUEST_TIMEOUT);
            FullHttpResponse response =
                new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
          }
        };
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    task.cancel();
    super.handlerRemoved(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    task.cancel();
    super.channelInactive(ctx);
  }

  /// On shutdown an idle connection is closed at once, a busy one after its response, see
  /// [TrackIngressConnectionsHandler#drain()]
  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt != TrackIngressConnectionsHandler.ShutdownEvent.SHUTDOWN) {
      super.userEventTriggered(ctx, evt);
      return;
    }
    closing = true;
    if (idle) {
      ctx.close();
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      idle = false;
    }
    if (!responding && msg instanceof LastHttpContent) {
      task.cancel();
      task =
          new TimeoutTask(ctx, config.responseTimeoutSeconds()) {
            @Override
            public void fire() {
              RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.UPSTREAM_TIMEOUT);
              // The exception closes the upstream; ExceptionHandler sends and logs the 504.
              ctx.fireExceptionCaught(new UpstreamResponseTimeoutException());
            }
          };
    } else {
      if (msg instanceof HttpRequest) {
        responding = false;
      }
      task.touch();
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (!responding) {
      responding = true;
      var response = (HttpResponse) msg;
      // If keep alive is not supported by the client and by the type of response we are sending,
      // HttpKeepAliveHandler will add connection: close. We don't need to check client capabilities
      // again.
      keepAlive = HttpUtil.isKeepAlive(response);
      if (closing) {
        // shutting down, the client opens a new connection, which the next instance serves
        keepAlive = false;
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      } else if (keepAlive) {
        // Keep-Alive header values is not specified in HTTP/1.1, however, it is a good practice
        // to avoid the client from sending a new requests while the server closes the connection
        // also server should send a 408 response before closing the connection
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Keep-Alive
        //noinspection deprecation
        response
            .headers()
            .set(
                HttpHeaderNames.KEEP_ALIVE, "timeout=" + config.readTimeoutSeconds() + ", max=123");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }
      task.cancel();
      task =
          new TimeoutTask(ctx, config.writeTimeoutSeconds()) {
            @Override
            public void fire() {
              RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.RESPONSE_WRITE_TIMEOUT);
              ctx.fireExceptionCaught(new WriteTimeoutException());
            }
          };
    }
    promise =
        promise
            .unvoid()
            .addListener(
                future -> {
                  task.touch();
                  if (msg instanceof LastHttpContent && closing) {
                    // the keep-alive handler saw the response before the header was set
                    ctx.close();
                    return;
                  }
                  if (msg instanceof LastHttpContent && keepAlive) {
                    idle = true;
                    task.cancel();
                    task =
                        new TimeoutTask(ctx, config.readTimeoutSeconds()) {
                          @Override
                          public void fire() {
                            proxyMetrics.ingressReceiveTimeoutKeepAlive.inc();
                            RequestLoggingHandler.reportOutcome(
                                ctx, RequestOutcome.REQUEST_TIMEOUT);
                            FullHttpResponse response =
                                new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
                            response
                                .headers()
                                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                          }
                        };
                  }
                });
    super.write(ctx, msg, promise);
  }

  private abstract static class TimeoutTask implements Runnable {
    private final ChannelHandlerContext ctx;
    private final long timeoutNs;
    private long lastTimeActiveNs;
    private ScheduledFuture<?> future;

    TimeoutTask(ChannelHandlerContext ctx, long timeoutSeconds) {
      this.ctx = ctx;
      this.timeoutNs = TimeUnit.SECONDS.toNanos(timeoutSeconds);
      lastTimeActiveNs = ctx.executor().ticker().nanoTime();
      schedule();
    }

    private void touch() {
      lastTimeActiveNs = ctx.executor().ticker().nanoTime();
    }

    private void cancel() {
      if (future != null) {
        future.cancel(false);
        future = null;
      }
    }

    private long delayNs() {
      long now = ctx.executor().ticker().nanoTime();
      long deadline = lastTimeActiveNs + timeoutNs;
      long delayNs = deadline - now;
      // long delaySeconds = TimeUnit.NANOSECONDS.toSeconds(delayNs);
      return delayNs;
    }

    private void schedule() {
      assert future == null;
      future = ctx.executor().schedule(this, delayNs(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void run() {
      if (!ctx.channel().isOpen()) {
        return;
      }
      long delayNs = delayNs();
      if (delayNs <= 0L) {
        future = null;
        fire();
      } else {
        future = ctx.executor().schedule(this, delayNs, TimeUnit.NANOSECONDS);
      }
    }

    public abstract void fire();
  }

  public static class UpstreamResponseTimeoutException extends ChannelException {}

  public static class WriteTimeoutException extends ChannelException {}
}
