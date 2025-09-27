package org.sensepitch.edge;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import java.util.NoSuchElementException;

/**
 * Implements timeouts for client connection. Also adds keep alive timeout and adds keep alive
 * header with correct timeout time.
 *
 * <p>Receive timeouts before the request arrives are handled internally. If the HttpRequest was
 * sent, we fire an exception.
 *
 * <p>This must be placed between the http codec handler and the keep alive handler.
 *
 * <p>The code is rather messy. The implementation is modifying the pipeline according to the
 * connection state and which timeout applies at the moment. Corner cases: Upstream might start
 * sending before we received the last content from ingress. Write notification of a response might
 * come after next request.
 *
 * <p>TODO: only works with HttpKeepAliveHandler next, maybe unify TODO: corner case when ingress
 * still sends and upstream is responding, however, we can do connection: close
 *
 * @author Jens Wilke
 */
public class ClientTimeoutHandler extends ReadTimeoutHandler {

  private final ConnectionConfig config;
  private boolean timeoutReachedClosing;
  private final boolean waitForSecondRequest;
  private final ProxyMetrics proxyMetrics;

  public ClientTimeoutHandler(ConnectionConfig config, ProxyMetrics proxyMetrics) {
    this(config, proxyMetrics, false);
  }

  /**
   * Uses identical timeout for keep alive and normal read. If we want to have different values, we
   * would need to reinitialize the timer again with the other timeout. Having separate keep alive
   * and read timeout is generally questionable. Maybe it makes sense to have a read timeout of 30
   * seconds and keep alive shorter, let's say 15 seconds. This would allow slower clients and get
   * rid of keep alive connections faster to save resources.
   */
  public ClientTimeoutHandler(
      ConnectionConfig config, ProxyMetrics proxyMetrics, boolean waitForSecondRequest) {
    super(config.readTimeoutSeconds());
    this.config = config;
    this.proxyMetrics = proxyMetrics;
    this.waitForSecondRequest = waitForSecondRequest;
  }

  /**
   * Ignore messages that arrive after the timeout reached, since the close for the connection is
   * pending and cannot be undone. If not yet timed out, switch to normal read timeout.
   */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (timeoutReachedClosing) {
      ReferenceCountUtil.release(msg);
      return;
    }
    if (msg instanceof LastHttpContent) {
      ctx.pipeline()
          .replace(
              this,
              "waitForUpstreamContentTimeout",
              new WaitForUpstreamContentHandler(config, proxyMetrics));
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    // assume the first message is HttpResponse, we never see another message since we are replaced
    assert msg instanceof HttpResponse;
    //noinspection ConstantValue
    if (msg instanceof HttpResponse reqeust) {
      upstreamStartsSending(this, config, proxyMetrics, ctx, reqeust, promise);
    } else {
      super.write(ctx, msg, promise);
    }
  }

  /** Send timeout and close. */
  @Override
  protected void readTimedOut(ChannelHandlerContext ctx) {
    if (!timeoutReachedClosing) {
      timeoutReachedClosing = true;
      if (!waitForSecondRequest) {
        proxyMetrics.ingressReceiveTimeoutFirstRequest.inc();
      } else {
        proxyMetrics.ingressReceiveTimeoutKeepAlive.inc();
      }
      FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
  }

  /** Switch to write timeout as soon as upstream is sending. */
  private static void upstreamStartsSending(
      ChannelHandler handler,
      ConnectionConfig config,
      ProxyMetrics proxyMetrics,
      ChannelHandlerContext ctx,
      HttpResponse response,
      ChannelPromise promise) {
    // If keep alive is not supported by the client and by the type of response we are sending,
    // HttpKeepAliveHandler will add connection: close. We don't need to check client capabilities
    // again.
    boolean keepAlive = HttpUtil.isKeepAlive(response);
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
    OngoingWriteTimeoutHandler newHandler =
        new OngoingWriteTimeoutHandler(config, proxyMetrics, keepAlive);
    ctx.pipeline().replace(handler, "writeTimeout", newHandler);
    ctx.pipeline().context(newHandler).write(response, promise);
  }

  private static void ingressStartsSending(
      ChannelHandler handler,
      ConnectionConfig config,
      ProxyMetrics proxyMetrics,
      ChannelHandlerContext ctx,
      Object msg) {
    ClientTimeoutHandler newHandler = new ClientTimeoutHandler(config, proxyMetrics);
    ctx.pipeline().replace(handler, "clientTimeoutOngoing", newHandler);
    ctx.pipeline().context(newHandler).fireChannelRead(msg);
  }

  /**
   * Wait until we see the response written. Add keep alive timeout header and switch to write
   * timeout.
   */
  static class WaitForUpstreamContentHandler extends IdleStateHandler {
    private final ConnectionConfig config;
    private final ProxyMetrics proxyMetrics;
    private boolean closed;

    public WaitForUpstreamContentHandler(ConnectionConfig config, ProxyMetrics proxyMetrics) {
      super(0, config.responseTimeoutSeconds(), 0);
      this.config = config;
      this.proxyMetrics = proxyMetrics;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      // Assume the first message is HttpResponse, we never see another message since we are
      // replaced
      assert msg instanceof HttpResponse;
      //noinspection ConstantValue
      if (closed) {
        ReferenceCountUtil.release(msg);
        return;
      }
      if (msg instanceof HttpResponse reqeust) {
        upstreamStartsSending(this, config, proxyMetrics, ctx, reqeust, promise);
      } else {
        super.write(ctx, msg, promise);
      }
    }

    @Override
    protected final void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt)
        throws Exception {
      assert evt.state() == IdleState.WRITER_IDLE;
      if (!closed) {
        closed = true;
        FullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.GATEWAY_TIMEOUT);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        // ctx.fireExceptionCaught(new UpstreamResponseTimeoutException());
        throw new UpstreamResponseTimeoutException();
      }
    }
  }

  static class OngoingWriteTimeoutHandler extends IdleStateHandler {

    private final ConnectionConfig config;
    private final boolean keepAlive;
    private final ProxyMetrics proxyMetrics;
    private boolean closed;

    public OngoingWriteTimeoutHandler(
        ConnectionConfig config, ProxyMetrics proxyMetrics, boolean keepAlive) {
      super(0, config.writeTimeoutSeconds(), 0);
      // super(config.writeTimeoutSeconds());
      this.config = config;
      this.proxyMetrics = proxyMetrics;
      this.keepAlive = keepAlive;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      ingressStartsSending(this, config, proxyMetrics, ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      if (closed) {
        ReferenceCountUtil.release(msg);
        return;
      }
      if (msg instanceof LastHttpContent) {
        // if not keep alive, the connection will be closed and all handlers removed.
        if (keepAlive) {
          promise =
              promise
                  .unvoid()
                  .addListener(
                      (ChannelFutureListener)
                          future -> {
                            if (future.isSuccess()) {
                              // channel might be closed anyways
                              if (future.channel().isActive()) {
                                try {
                                  ctx.pipeline()
                                      .replace(
                                          OngoingWriteTimeoutHandler.this,
                                          "keepAliveReadTimeout",
                                          new ClientTimeoutHandler(config, proxyMetrics, true));
                                } catch (NoSuchElementException ex) {
                                  // ignore, race of new read and write notification
                                }
                              }
                            }
                          });
        }
      }
      super.write(ctx, msg, promise);
    }

    @Override
    protected final void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt)
        throws Exception {
      assert evt.state() == IdleState.WRITER_IDLE;
      if (!closed) {
        closed = true;
        ctx.fireExceptionCaught(new WriteTimeoutException());
        /*-
        DownstreamProgress.complete(ctx.channel());
        // TODO: send different marker so request logging can detect abort
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
            .addListener(ChannelFutureListener.CLOSE);
         */
      }
    }
  }

  public static class UpstreamResponseTimeoutException extends ChannelException {}

  public static class WriteTimeoutException extends ChannelException {}
}
