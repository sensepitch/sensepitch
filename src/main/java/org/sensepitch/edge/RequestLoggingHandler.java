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
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Ticker;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import javax.net.ssl.SSLSession;

/// Listens to incoming and outgoing http messages and collects all relevant information during
/// request processing and calls the request logger implementation when the last content is written.
///
/// It also assigns each request its W3C trace context and sets the `traceparent` header that is
/// sent to upstream, see [TraceContext] and [TraceContextConfig].
///
/// Note on concurrency: there is no concurrent activity on this object. The downstream request
/// comes from one thread, after submitting the upstream request, writes come from the upstream
/// reading thread
///
/// @author Jens Wilke
public class RequestLoggingHandler extends ChannelDuplexHandler implements RequestLogInfo {

  private static final ProxyLogger DEBUG = ProxyLogger.get(RequestLoggingHandler.class);

  private static final CountByteIoHandler DUMMY_COUNT_BYTE_IO_HANDLER = new CountByteIoHandler();

  /// Construct a mock http request in case we don't have a request, which can happen if the request
  /// was malformed or receive timed out. We don't use a HttpRequest singleton, maybe we want to add
  /// headers, like set the host, if its known.
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

  /// When we start sending the first byte
  private long responseStartedTimeNanos;

  /// When everything was received
  private long responseReceivedTimeNanos;

  private long bytesReceivedStart;
  private long bytesSentStart;
  CountByteIoHandler countByteIoHandler;

  private final ProxyMetrics metrics;
  private final boolean trustTraceparent;

  /// Supplies the id of the active configuration, `null` in tests
  private final Supplier<String> configIds;

  private long requestBodyBytes;
  private Upstream upstream;

  /// In flight counter of `upstream`, decremented when the request is logged
  private LongAdder upstreamInFlight;

  private RequestOutcome outcome;
  private int upstreamStatus;
  private String route;
  private String configId;
  private ProxyMetrics.DeflectorOutcome protectionOutcome;
  private TraceContext traceContext;
  private String clientTraceparent;

  /// TLS session data, read once per connection, when the first request is logged
  private boolean tlsResolved;

  private String tlsProtocol;
  private String tlsCipher;

  /// Handler that never trusts a `traceparent` header from the client
  public RequestLoggingHandler(ProxyMetrics metrics, RequestLogger logger) {
    this(metrics, logger, false);
  }

  /// @param trustTraceparent continue the trace of the client, see
  ///   [TraceContextConfig#trustTraceparent()]
  public RequestLoggingHandler(
      ProxyMetrics metrics, RequestLogger logger, boolean trustTraceparent) {
    this(metrics, logger, trustTraceparent, null);
  }

  /// @param configIds supplies the id of the active configuration, read when a request starts
  public RequestLoggingHandler(
      ProxyMetrics metrics,
      RequestLogger logger,
      boolean trustTraceparent,
      Supplier<String> configIds) {
    this.logger = logger;
    this.metrics = metrics;
    this.trustTraceparent = trustTraceparent;
    this.configIds = configIds;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    ticker = ctx.executor().ticker();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    countByteIoHandler = ctx.pipeline().get(CountByteIoHandler.class);
    if (countByteIoHandler == null) {
      countByteIoHandler = DUMMY_COUNT_BYTE_IO_HANDLER;
    }
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
      requestBodyBytes = 0;
      configId = configIds != null ? configIds.get() : null;
      assignTraceContext(request);
    }
    // count before passing on, the content is released further down the pipeline
    if (msg instanceof HttpContent content) {
      requestBodyBytes += content.content().readableBytes();
    }
    if (msg instanceof LastHttpContent) {
      requestCompleteTimeNanos = ticker.nanoTime();
    }
    super.channelRead(ctx, msg);
  }

  /// Assigns the trace context of the request and sets the `traceparent` header for upstream. A
  /// trusted, valid header of the client is continued with a new span id, otherwise a new trace is
  /// started and a `tracestate` of the client is removed, since it belongs to the client's trace.
  private void assignTraceContext(HttpRequest request) {
    clientTraceparent = request.headers().get(TraceContext.TRACEPARENT);
    TraceContext parent = trustTraceparent ? TraceContext.parse(request) : null;
    if (parent != null) {
      traceContext = parent.child();
    } else {
      traceContext = TraceContext.generate();
      request.headers().remove(TraceContext.TRACESTATE);
    }
    request.headers().set(TraceContext.TRACEPARENT, traceContext.header());
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
        promise = promise.unvoid();
        promise.addListener(
            future -> {
              // DEBUG.info("write complete, success=" + future.isSuccess());
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
    if (!tlsResolved) {
      resolveTls(ctx);
    }
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
    upstream = null;
    if (upstreamInFlight != null) {
      upstreamInFlight.decrement();
      upstreamInFlight = null;
    }
    outcome = null;
    upstreamStatus = 0;
    route = null;
    configId = null;
    protectionOutcome = null;
    traceContext = null;
    clientTraceparent = null;
  }

  /// The SNI handler replaces itself by the SSL handler, so it is looked up when the handshake is
  /// done, which is the case when a request was received
  private void resolveTls(ChannelHandlerContext ctx) {
    tlsResolved = true;
    SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
    if (sslHandler == null) {
      return;
    }
    SSLSession session = sslHandler.engine().getSession();
    if (session != null) {
      tlsProtocol = session.getProtocol();
      tlsCipher = session.getCipherSuite();
    }
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

  /// The request logging handler of the pipeline
  ///
  /// @return the handler, or `null` if the pipeline has none, e.g. in tests
  static RequestLoggingHandler find(ChannelHandlerContext ctx) {
    return ctx.pipeline().get(RequestLoggingHandler.class);
  }

  /// Reports why the request ends to the request logging handler of the pipeline, if any. Must be
  /// called before the response is written, the request may be logged when the write completes.
  static void reportOutcome(ChannelHandlerContext ctx, RequestOutcome outcome) {
    RequestLoggingHandler handler = find(ctx);
    if (handler != null) {
      handler.setOutcome(outcome);
    }
  }

  // The setters below ignore reports while no request is in progress, so a late report, e.g. after
  // the response was written and the request logged, cannot leak into the next request.

  /// Reports why the request ends, the first report of a request wins
  public void setOutcome(RequestOutcome outcome) {
    if (request != null && this.outcome == null) {
      this.outcome = outcome;
    }
  }

  /// Called by the [FallbackHandler] with the status of the replaced upstream response
  public void setUpstreamStatus(int status) {
    if (request != null) {
      this.upstreamStatus = status;
    }
  }

  /// Called by the [SiteSelectorHandler] with the matched route
  public void setRoute(String route) {
    if (request != null) {
      this.route = route;
    }
  }

  /// Called by the [DeflectorHandler] with its outcome
  public void setProtectionOutcome(ProxyMetrics.DeflectorOutcome outcome) {
    if (request != null) {
      this.protectionOutcome = outcome;
    }
  }

  /// Called by the [DownstreamHandler] with the upstream selected for the request
  public void setUpstream(Upstream upstream) {
    if (request != null) {
      this.upstream = upstream;
      upstreamInFlight = metrics.upstreamRequestsInFlight(upstream);
      upstreamInFlight.increment();
    }
  }

  public void setException(Throwable throwable) {
    if (error == null) {
      error = throwable;
    }
  }

  /// Write a log if the connection is closed before the full response is received. Rationale: In
  /// case of a PUT or POST the request may have an effect, so we should also log it. In this case
  /// we set contentBytes to 0, because the contentBytes do not reflect
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (request != null) {
      metrics.ingressRequestsAborted.increment();
      contentBytes = 0;
      response = ABORTED_RESPONSE;
      setOutcome(RequestOutcome.CLIENT_ABORTED);
      log(ctx);
    }
    super.channelInactive(ctx);
  }

  @Override
  public String connectionId() {
    return LogTarget.localChannelId(channel);
  }

  @Override
  public int connectionRequestCount() {
    return requestCount;
  }

  @Override
  public InetAddress clientAddress() {
    if (channel != null && channel.remoteAddress() instanceof InetSocketAddress address) {
      return address.getAddress();
    }
    return null;
  }

  @Override
  public int clientPort() {
    if (channel != null && channel.remoteAddress() instanceof InetSocketAddress address) {
      return address.getPort();
    }
    return 0;
  }

  @Override
  public int serverPort() {
    if (channel != null && channel.localAddress() instanceof InetSocketAddress address) {
      return address.getPort();
    }
    return 0;
  }

  @Override
  public RequestOutcome outcome() {
    if (outcome != null) {
      return outcome;
    }
    return upstream != null ? RequestOutcome.COMPLETED : RequestOutcome.EDGE_RESPONSE;
  }

  @Override
  public int upstreamStatus() {
    return upstreamStatus;
  }

  @Override
  public String route() {
    return route;
  }

  @Override
  public String configId() {
    return configId;
  }

  @Override
  public String protectionOutcome() {
    return protectionOutcome != null ? protectionOutcome.value() : null;
  }

  @Override
  public String upstreamName() {
    return upstream != null ? upstream.name() : null;
  }

  @Override
  public String upstreamTarget() {
    return upstream != null ? upstream.target() : null;
  }

  @Override
  public String tlsProtocol() {
    return tlsProtocol;
  }

  @Override
  public String tlsCipher() {
    return tlsCipher;
  }

  @Override
  public String traceId() {
    return traceContext != null ? traceContext.traceId() : null;
  }

  @Override
  public String spanId() {
    return traceContext != null ? traceContext.spanId() : null;
  }

  @Override
  public String traceFlags() {
    return traceContext != null ? traceContext.flags() : null;
  }

  @Override
  public String clientTraceparent() {
    return clientTraceparent;
  }

  @Override
  public long requestBodyBytes() {
    return requestBodyBytes;
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
