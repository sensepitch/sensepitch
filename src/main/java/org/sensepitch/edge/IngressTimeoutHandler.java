
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

/**
 * <p>Watches the request and response pass through and implement timeouts for
 * the request, response and write. There is only one timeout active at a point in time.
 * The response timeout is actually a timeout for our upstream. Once the upstream starts responding
 * the handler switches to write timeout. The write timeout triggers when no write complete any
 * more, which covers upstream and receiver stalls.
 *
 * <p>This must be placed between the http codec handler and the keep alive handler.
 *
 *<p>TODO: only works with HttpKeepAliveHandler next, maybe unify TODO: corner case when ingress
 * still sends and upstream is responding, however, we can do connection: close
 *
 * @author Jens Wilke
 */
public final class IngressTimeoutHandler extends ChannelDuplexHandler {

  public static ProxyLogger LOG = ProxyLogger.get(IngressTimeoutHandler.class);

  private final ConnectionConfig config;
  private boolean responding = false;
  private TimeoutTask task;
  private final ProxyMetrics proxyMetrics;
  private boolean keepAlive;

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
    task = new TimeoutTask(ctx, config.readTimeoutSeconds()) {
      @Override
      public void fire() {
        proxyMetrics.ingressReceiveTimeoutFirstRequest.inc();
        FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
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

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (!responding && msg instanceof LastHttpContent) {
      // LOG.info("awaiting response");
      task.cancel();
      task = new TimeoutTask(ctx, config.responseTimeoutSeconds()) {
        @Override
        public void fire() {
          // TODO: Metrics
          FullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.GATEWAY_TIMEOUT);
          response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
          // TODO: which one?
          ctx.fireExceptionCaught(new UpstreamResponseTimeoutException());
          // throw new ClientTimeoutHandler.UpstreamResponseTimeoutException();
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
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (!responding) {
      // LOG.info("responding");
      responding = true;
      var response = (HttpResponse) msg;
      // If keep alive is not supported by the client and by the type of response we are sending,
      // HttpKeepAliveHandler will add connection: close. We don't need to check client capabilities
      // again.
      keepAlive = HttpUtil.isKeepAlive(response);
      if (keepAlive) {
        // Keep-Alive header values is not specified in HTTP/1.1, however, it is a good practice
        // to avoid the client from sending a new requests while the server closes the connection
        // also server should send a 408 response before closing the connection
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Keep-Alive
        //noinspection deprecation
        response
          .headers()
          .set(HttpHeaderNames.KEEP_ALIVE, "timeout=" + config.readTimeoutSeconds() + ", max=123");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }
      task.cancel();
      task = new TimeoutTask(ctx, config.writeTimeoutSeconds()) {
        @Override
        public void fire() {
          // TODO: Metrics
          ctx.fireExceptionCaught(new WriteTimeoutException());
        }
      };
    }
    promise = promise.unvoid().addListener(future -> {
      task.touch();
      if (msg instanceof LastHttpContent && keepAlive) {
        // LOG.info("awaiting another request");
        task.cancel();
        task = new TimeoutTask(ctx, config.readTimeoutSeconds()) {
          @Override
          public void fire() {
            proxyMetrics.ingressReceiveTimeoutKeepAlive.inc();
            FullHttpResponse response =
              new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
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

