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
import io.netty.handler.codec.http.HttpStatusClass;
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
/// It also manages keep-alive, in place of Netty's `HttpServerKeepAliveHandler`, because the
/// keep-alive decision and the idle read timeout depend on each other. The connection is kept open
/// after a response only if all of these hold, otherwise the response gets `Connection: close` and
/// the connection is closed after it:
/// - the request allows it: HTTP/1.1 without `Connection: close`, or HTTP/1.0 with
///   `Connection: keep-alive`
/// - a request with a body (`Content-Length` above 0, or chunked) was received completely before
///   the response started. A response that starts while the client is still sending the body,
///   e.g. an early 413 of the upstream, leaves the rest of the body unread; not reusing the
///   connection is the simple way to stay in sync with the client. A request without a body is
///   complete with its head: handlers that answer on the head, e.g. a fixed response, write
///   before the codec passes on the empty `LastHttpContent` that follows.
/// - the proxy is not shutting down, see [#userEventTriggered]
/// - the response allows it: no `Connection: close`, and its end is defined by the message itself
///   (`Content-Length`, chunked, `multipart`, or 204), the same rule as Netty 4.2's
///   `HttpServerKeepAliveHandler`
///
/// After such an early response the connection is closed only when the rest of the body was read,
/// or when the client sends nothing for the read timeout, counted with phase `after_response`.
/// Closing a socket with unread received data makes the TCP stack send a reset, which may discard
/// the response at the client before it was read (what NGINX's `lingering_close` avoids).
///
/// Informational responses (1xx, e.g. `100 Continue`) pass through, the final response follows.
/// HTTP pipelining is not supported, [DownstreamHandler] rejects a request during an exchange.
///
/// Place it after the HTTP codec, and after handlers that may answer a request themselves, so it
/// sees every response.
///
/// @author Jens Wilke
public final class IngressTimeoutHandler extends ChannelDuplexHandler {

  private static final ProxyLogger LOG = ProxyLogger.get(IngressTimeoutHandler.class);

  /// Content type prefix of a multipart response, which delimits its own end
  private static final String MULTIPART_PREFIX = "multipart";

  private final ConnectionConfig config;
  private boolean responding = false;
  private TimeoutTask task;
  private final ProxyMetrics proxyMetrics;

  /// The connection stays open after the current response, decided when the response starts
  private boolean keepAlive;

  /// The current request allows keep-alive, see [HttpUtil#isKeepAlive]
  private boolean requestKeepAlive;

  /// The client is still sending the body of the request, see the class doc
  private boolean receiving;

  /// The response ended before the request body, the connection is closed after the body, see
  /// the class doc
  private boolean closeAfterRequest;

  /// The response being written is informational (1xx), the final response follows. Set by every
  /// response head, so a 1xx without its `LastHttpContent` cannot hide the final response
  private boolean informational;

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
            proxyMetrics.countReceiveTimeout(ctx, "first_request");
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
    if (msg instanceof HttpRequest request) {
      idle = false;
      responding = false;
      receiving = hasBody(request);
      requestKeepAlive = HttpUtil.isKeepAlive(request);
    }
    if (msg instanceof LastHttpContent) {
      receiving = false;
      if (closeAfterRequest) {
        // DownstreamHandler drops the rest of the body of an ended exchange
        super.channelRead(ctx, msg);
        ctx.close();
        return;
      }
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
      task.touch();
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse response) {
      informational = isInformational(response);
    }
    if (informational) {
      // a 1xx and its content, the final response follows, nothing to decide or time yet
      super.write(ctx, msg, promise);
      return;
    }
    if (!responding) {
      responding = true;
      var response = (HttpResponse) msg;
      keepAlive =
          requestKeepAlive
              && !receiving
              // shutting down: the client opens a new connection, which the next instance serves
              && !closing
              && HttpUtil.isKeepAlive(response)
              && isSelfDefinedMessageLength(response);
      if (!keepAlive) {
        HttpUtil.setKeepAlive(response, false);
      } else {
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
                  if (!(msg instanceof LastHttpContent)) {
                    return;
                  }
                  if (receiving) {
                    // an early response, keepAlive is false
                    closeAfterRequest = true;
                    task.cancel();
                    task =
                        new TimeoutTask(ctx, config.readTimeoutSeconds()) {
                          @Override
                          public void fire() {
                            proxyMetrics.countReceiveTimeout(ctx, "after_response");
                            ctx.close();
                          }
                        };
                    return;
                  }
                  if (!keepAlive || closing) {
                    // closing: the shutdown started after the response headers were written
                    ctx.close();
                    return;
                  }
                  idle = true;
                  task.cancel();
                  task =
                      new TimeoutTask(ctx, config.readTimeoutSeconds()) {
                        @Override
                        public void fire() {
                          proxyMetrics.countReceiveTimeout(ctx, "keep_alive");
                          RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.REQUEST_TIMEOUT);
                          FullHttpResponse response =
                              new DefaultFullHttpResponse(
                                  HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
                          response
                              .headers()
                              .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                        }
                      };
                });
    super.write(ctx, msg, promise);
  }

  /// The request announces a body, so the client may still be sending when the response starts
  private static boolean hasBody(HttpRequest request) {
    return HttpUtil.isTransferEncodingChunked(request)
        || HttpUtil.getContentLength(request, 0L) > 0;
  }

  /// A 1xx response, which precedes the final response, see [#write]
  private static boolean isInformational(HttpResponse response) {
    return response.status().codeClass() == HttpStatusClass.INFORMATIONAL;
  }

  /// The client can tell the end of the response without the connection closing, the condition of
  /// keep-alive, see the class doc
  private static boolean isSelfDefinedMessageLength(HttpResponse response) {
    String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
    return HttpUtil.isContentLengthSet(response)
        || HttpUtil.isTransferEncodingChunked(response)
        || (contentType != null
            && contentType.regionMatches(true, 0, MULTIPART_PREFIX, 0, MULTIPART_PREFIX.length()))
        || response.status().code() == HttpResponseStatus.NO_CONTENT.code();
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
