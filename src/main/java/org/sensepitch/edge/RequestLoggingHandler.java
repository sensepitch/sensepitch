package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Ticker;

/**
 * Listens to incoming and outgoing http messages and collects all relevant information
 * during request processing and calls the request logger implementation when the last
 * content is written.
 *
 * <p>Note on concurrency: there is no concurrent activity on this object.
 * The downstream request comes from one thread, after submitting the upstream request,
 * writes come from the upstream reading thread
 *
 * @author Jens Wilke
 */
public class RequestLoggingHandler extends ChannelDuplexHandler implements RequestLogInfo {

  static ProxyLogger DEBUG = ProxyLogger.get(RequestLoggingHandler.class);

  private long contentBytes = 0;
  private HttpRequest request;
  private HttpResponse response;
  private long requestStartTime;
  private final RequestLogger logger;
  private Channel channel;
  private Throwable error;
  private HttpHeaders trailingHeaders;
  private int requestCount;
  private Ticker ticker;
  private long connectionEstablishedNanos;
  private long requestStartTimeNanos;
  private long requestCompleteTimeNanos;
  private long responseStartedTimeNanos;
  private long responseReceivedTimeNanos;

  public RequestLoggingHandler(RequestLogger logger) {
    this.logger = logger;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    ticker = ctx.executor().ticker();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    connectionEstablishedNanos = ticker.nanoTime();
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception{
    if (msg instanceof HttpRequest) {
      request = (HttpRequest) msg;
      requestStartTime = System.currentTimeMillis();
      requestStartTimeNanos = ticker.nanoTime();
    }
    if (msg instanceof LastHttpContent) {
      requestCompleteTimeNanos = ticker.nanoTime();
    }
    super.channelRead(ctx, msg);
  }

  /**
   * Set response start time when the output buffer becomes full.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (!ctx.channel().isWritable() && responseStartedTimeNanos == 0) {
      responseStartedTimeNanos = ticker.nanoTime();
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof HttpResponse) {
      response = (HttpResponse) msg;
      contentBytes = 0;
    }
    if (request == null) {
      request = constructMockHttpRequest(ctx);
    }
    // HttpResponse may have content as well
    if (msg instanceof HttpContent httpContent) {
      contentBytes += httpContent.content().readableBytes();
    }
    if (msg instanceof LastHttpContent lastHttpContent) {
      long now = ticker.nanoTime();
      if (responseStartedTimeNanos == 0) {
        responseStartedTimeNanos = now;
      }
      // not yet received LastHttpContent from ingress, assume it was complete already
      // special case may happen (in testing), if upstream response is already processed
      // before we receive the last content
      // This can also be a request timeout
      if (requestCompleteTimeNanos == 0) {
        requestCompleteTimeNanos = now;
      }
      if (requestStartTimeNanos == 0) {
        requestStartTimeNanos = now;
      }
      trailingHeaders = lastHttpContent.trailingHeaders();
      channel = ctx.channel();
      promise.addListener(future -> {
          responseReceivedTimeNanos = ticker.nanoTime();
          error = future.cause();
          try {
            logger.logRequest(this);
            requestCount++;
          } catch (Throwable e) {
            DEBUG.error(ctx.channel(), "Error logging request", e);
          }
          // reset times for keep alive requests
          requestStartTime = System.currentTimeMillis();
          connectionEstablishedNanos = now;
          requestCompleteTimeNanos = responseStartedTimeNanos = 0;
          request = null;
        }
      );
    }
    super.write(ctx, msg, promise);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (request != null) {
      error = cause;
      try {
        logger.logRequest(this);
        requestCount++;
      } catch (Throwable e) {
        DEBUG.error(ctx.channel(), "Error logging request", e);
      }
      request = null;
    }
    super.exceptionCaught(ctx, cause);
  }


  /**
   * Construct a mock http request in case we don't have a request, which can happen if
   * the request was malformed or receive timed out. We don't use a HttpRequest singleton,
   * maybe we want to add headers, like set the host, if its known.
   */
  private HttpRequest constructMockHttpRequest(ChannelHandlerContext ctx) {
    HttpRequest request = new DefaultFullHttpRequest(NIL_VERSION, NIL_METHOD, "/");
    request.headers().set(HttpHeaderNames.HOST, SanitizeHostHandler.NIL_HOST);
    return request;
  }

  @Override public String requestId() { return LogTarget.localChannelId(channel) + "/" + requestCount; }
  @Override public Channel channel() { return channel; }
  @Override  public Throwable error() { return error; }
  @Override public HttpRequest request() { return request; }
  @Override public HttpResponse response() { return response; }
  @Override public HttpHeaders trailingHeaders() { return trailingHeaders; }
  @Override public long contentBytes() { return contentBytes; }
  @Override public long requestStartTimeMillis() { return requestStartTime; }

  @Override
  public long receiveDurationNanos() {
    return requestCompleteTimeNanos - connectionEstablishedNanos;
  }

  @Override
  public long responseTimeNanos() {
    return responseStartedTimeNanos - requestCompleteTimeNanos;
  }

  @Override
  public long totalDurationNanos() {
    return responseReceivedTimeNanos - requestStartTimeNanos;
  }

  @Override
  public String requestHeaderHost() {
    return request.headers().get(HttpHeaderNames.HOST);
  }

}
