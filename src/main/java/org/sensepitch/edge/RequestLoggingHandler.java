package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Ticker;

/**
 * Listens to incoming and outgoing http messages and collects all relevant information during
 * request processing and calls the request logger implementation when the last content is written.
 *
 * <p>Note on concurrency: there is no concurrent activity on this object. The downstream request
 * comes from one thread, after submitting the upstream request, writes come from the upstream
 * reading thread
 *
 * @author Jens Wilke
 */
public class RequestLoggingHandler extends ChannelDuplexHandler implements RequestLogInfo {

  static ProxyLogger DEBUG = ProxyLogger.get(RequestLoggingHandler.class);

  /**
   * Construct a mock http request in case we don't have a request, which can happen if the request
   * was malformed or receive timed out. We don't use a HttpRequest singleton, maybe we want to add
   * headers, like set the host, if its known.
   */
  private static final HttpRequest MOCK_REQUEST =
      new DefaultHttpRequest(NIL_VERSION, NIL_METHOD, "/");

  private static final HttpResponse ABORTED_RESPONSE =
      new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(499, "Aborted"));

  static {
    MOCK_REQUEST.headers().set(HttpHeaderNames.HOST, SanitizeHostHandler.NIL_HOST);
  }

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

  /** When we start sending the first byte */
  private long responseStartedTimeNanos;

  /** When everything was received */
  private long responseReceivedTimeNanos;

  private long bytesReceivedStart;
  private long bytesSentStart;
  CountByteIoHandler countByteIoHandler;

  private final ProxyMetrics metrics;

  public RequestLoggingHandler(ProxyMetrics metrics, RequestLogger logger) {
    this.logger = logger;
    this.metrics = metrics;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    ticker = ctx.executor().ticker();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    countByteIoHandler = ctx.pipeline().get(CountByteIoHandler.class);
    connectionEstablishedNanos = ticker.nanoTime();
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      metrics.ingressRequestsStarted.increment();
      request = (HttpRequest) msg;
      requestStartTime = System.currentTimeMillis();
      requestStartTimeNanos = ticker.nanoTime();
      response = null;
    }
    if (msg instanceof LastHttpContent) {
      requestCompleteTimeNanos = ticker.nanoTime();
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void flush(ChannelHandlerContext ctx) throws Exception {
    if (responseStartedTimeNanos == 0) {
      responseStartedTimeNanos = ticker.nanoTime();
    }
    super.flush(ctx);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse) {
      response = (HttpResponse) msg;
      contentBytes = 0;
    }
    // HttpResponse may have content as well
    if (msg instanceof HttpContent httpContent) {
      contentBytes += httpContent.content().readableBytes();
    }
    if (msg instanceof LastHttpContent lastHttpContent) {
      consolidateTimes();
      trailingHeaders = lastHttpContent.trailingHeaders();
      // in case of a timeout we send a timeout response. make sure we have
      // a mock request to not confuse loggers
      if (request == null) {
        // don't send to logging
        // request = MOCK_REQUEST;
      } else {
        promise
          .unvoid()
          .addListener(
            future -> {
              if (request == null) {
                return;
              }
              metrics.ingressRequestsCompleted.increment();
              // TODO: switch response in case of error?
              setException(future.cause());
              log(ctx);
            });
      }
    }
    super.write(ctx, msg, promise);
  }

  private void log(ChannelHandlerContext ctx) {
    channel = ctx.channel();
    responseReceivedTimeNanos = ticker.nanoTime();
    // TODO: in case of error, maybe different status code? maybe set content to 0?
    try {
      logger.logRequest(this);
      requestCount++;
    } catch (Throwable e) {
      DEBUG.error(ctx.channel(), "Error logging request", e);
    }
    // reset times for keep alive requests
    requestStartTime = System.currentTimeMillis();
    connectionEstablishedNanos = ticker.nanoTime();
    requestCompleteTimeNanos = responseStartedTimeNanos = 0;
    bytesReceivedStart = countByteIoHandler.getBytesReceived();
    bytesSentStart = countByteIoHandler.getBytesSent();
    request = null;
  }

  private void consolidateTimes() {
    long now = ticker.nanoTime();
    // optional, since done by flush, however, we save a timer call
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
  }

  public void setException(Throwable throwable) {
    if (error == null) {
      error = throwable;
    }
  }

  /**
   * Write a log if the connection is closed before the full response is received. Rationale: In
   * case of a PUT or POST the request may have an effect, so we should also log it. In this case
   * we set contentBytes to 0, because the contentBytes do not reflect
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (request != null) {
      metrics.ingressRequestsAborted.increment();
      contentBytes = 0;
      response = ABORTED_RESPONSE;
      log(ctx);
    }
    super.channelInactive(ctx);
  }

  @Override
  public String requestId() {
    return LogTarget.localChannelId(channel) + "/" + requestCount;
  }

  @Override
  public Channel channel() {
    return channel;
  }

  @Override
  public Throwable error() {
    return error;
  }

  @Override
  public HttpRequest request() {
    return request;
  }

  @Override
  public HttpResponse response() {
    return response;
  }

  @Override
  public HttpHeaders trailingHeaders() {
    return trailingHeaders;
  }

  @Override
  public long contentBytes() {
    return contentBytes;
  }

  @Override
  public long bytesSent() {
    return countByteIoHandler.getBytesSent() - bytesSentStart;
  }

  @Override
  public long bytesReceived() {
    return countByteIoHandler.getBytesReceived() - bytesReceivedStart;
  }

  @Override
  public long requestStartTimeMillis() {
    return requestStartTime;
  }

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

  public boolean isResponseStarted() {
    return response != null;
  }

  public boolean isRequestReceived() {
    return request != null;
  }

  public boolean isRequestComplete() {
    return requestCompleteTimeNanos > 0;
  }
}
