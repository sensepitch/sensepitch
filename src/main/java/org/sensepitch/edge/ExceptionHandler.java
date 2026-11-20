package org.sensepitch.edge;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import javax.net.ssl.SSLHandshakeException;
import lombok.extern.slf4j.Slf4j;

/// Counts connection failures in [ProxyMetrics#ingressErrorCounter] and completes the connection
/// without exposing exception details to the peer. Every failure is a request level event, so it
/// is logged at debug only, with phase, channel and cause, see [ProxyLogger] for the levels; an
/// unexpected one, possibly a fault of ours, is counted as type `other`. Resets and common TLS
/// rejections are counted without a debug line.
///
/// @author Jens Wilke
@Slf4j
public class ExceptionHandler extends ChannelInboundHandlerAdapter {

  private static final ProxyLogger LOG = ProxyLogger.get(ExceptionHandler.class);

  static final ProxyLogger.RequestProblem HANDSHAKE_ERROR =
      new ProxyLogger.RequestProblem(
          "ExceptionHandler.handshakeError",
          "sensepitch_ingress_request_errors{phase=\"handshake\",type=\"other\"}");

  static final ProxyLogger.RequestProblem TIMEOUT =
      new ProxyLogger.RequestProblem(
          "ExceptionHandler.timeout", "sensepitch_ingress_request_errors{type=\"timeout\"}");

  static final ProxyLogger.RequestProblem EXCEPTION =
      new ProxyLogger.RequestProblem(
          "ExceptionHandler.exception", "sensepitch_ingress_request_errors{type=\"other\"}");

  private boolean sslHandshakeComplete;
  private final ProxyMetrics metrics;

  public ExceptionHandler(ProxyMetrics metrics) {
    this.metrics = metrics;
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

  // Extract all exception strings from error log:
  // journalctl -u NAME -n 5000 | grep ERROR | awk 'match($0, /[^ ]+Exception.*/){ print substr($0,
  // RSTART ) }' | sort | uniq }
  //
  // Commonly seen:
  // io.netty.handler.codec.DecoderException: io.netty.handler.ssl.NotSslRecordException: not an
  // SSL/TLS record
  // io.netty.handler.codec.DecoderException:
  // io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException:
  // error:100000b8:SSL routines:OPENSSL_internal:NO_SHARED_CIPHER
  // io.netty.handler.codec.DecoderException:
  // io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException:
  // error:100000f0:SSL routines:OPENSSL_internal:UNSUPPORTED_PROTOCOL
  // io.netty.handler.codec.DecoderException:
  // io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException:
  // error:100003f2:SSL routines:OPENSSL_internal:SSLV3_ALERT_UNEXPECTED_MESSAGE
  // io.netty.handler.ssl.ReferenceCountedOpenSslEngine$OpenSslHandshakeException is subtype of
  // SSLHandshakeException

  /// True for all variants of common ssl decoder exceptions
  boolean isSslDecoderException(Throwable cause) {
    Throwable decoderException = null;
    if (cause instanceof DecoderException) {
      decoderException = cause.getCause();
    }
    return decoderException instanceof SSLHandshakeException
        || decoderException instanceof NotSslRecordException;
  }

  // java.net.SocketException: Connection reset
  // java.net.SocketException: Connection reset 112.254.156.186 java.net.SocketException: Connection
  // reset
  // java.net.SocketException: Connection reset 2053:c0:3700:6157:a256:3692:31aa:1235
  // java.net.SocketException: Connection reset
  // java.io.IOException: Connection reset by peer java.io.IOException: Connection reset by peer
  // java.nio.channels.ClosedChannelException java.nio.channels.ClosedChannelException
  // io.netty.handler.ssl.SslClosedEngineException: SSLEngine closed already
  // io.netty.handler.ssl.SslClosedEngineException: SSLEngine closed already
  // java.io.IOException: Broken pipe java.io.IOException: Broken pipe

  /// True for all known variants of ingress connection reset or closure while we are still
  /// receiving or sending. If ingress connections resets we don't stop writes but simple ignore the
  /// exceptions that this might result
  boolean isConnectionReset(Throwable cause) {
    return cause instanceof SslClosedEngineException
        || cause instanceof ClosedChannelException
        || (cause instanceof IOException
            && cause.getMessage() != null
            && (cause.getMessage().startsWith("Connection reset")
                || cause.getMessage().startsWith("Broken pipe")))
        || (cause instanceof SocketException
            && cause.getMessage() != null
            && cause.getMessage().startsWith("Connection reset"));
  }

  /// Handle an exception, this might be a connection reset a timeout etc.
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    // connection reset might include the IP address, not good
    boolean connectionReset = isConnectionReset(cause);
    if (!sslHandshakeComplete) {
      String phase = "handshake";
      if (isSslDecoderException(cause)) {
        metrics.countIngressError(ctx, phase, "ssl");
      } else if (connectionReset) {
        metrics.countIngressError(ctx, phase, "reset");
      } else {
        metrics.countIngressError(ctx, phase, "other");
        LOG.requestProblem(
            HANDSHAKE_ERROR, ctx.channel(), "handshake error, phase=handshake", cause);
      }
      completeAndClose(ctx);
      return;
    }
    // logging handler does know exact state
    RequestLoggingHandler loggingHandler =
        ctx.channel().pipeline().get(RequestLoggingHandler.class);
    loggingHandler.setException(cause);
    String phase;
    if (!loggingHandler.isRequestReceived()) {
      phase = "requesting";
    } else if (!loggingHandler.isRequestComplete()) {
      phase = "upload";
    } else if (!loggingHandler.isResponseStarted()) {
      phase = "waiting";
    } else {
      phase = "responding";
    }
    // for connection reset we don't log or respond, just count
    if (connectionReset) {
      metrics.countIngressError(ctx, phase, "reset");
      completeAndClose(ctx);
      return;
    }
    // our own timeouts are no unexpected error, keep them apart from "other"
    boolean timeout =
        cause instanceof IngressTimeoutHandler.UpstreamResponseTimeoutException
            || cause instanceof IngressTimeoutHandler.WriteTimeoutException;
    metrics.countIngressError(ctx, phase, timeout ? "timeout" : "other");
    // a more specific outcome, e.g. a timeout, was reported before the exception, it wins
    loggingHandler.setOutcome(RequestOutcome.EDGE_ERROR);
    LOG.requestProblem(timeout ? TIMEOUT : EXCEPTION, ctx.channel(), "phase=" + phase, cause);
    // send a proper response if possible, log via standard request log
    if (loggingHandler.isRequestReceived() && !loggingHandler.isResponseStarted()) {
      // The exception is recorded in the request log. It is not sent to the client, it may reveal
      // internal addresses, and a line break in its message would make the status line invalid.
      completeWithError(
          ctx,
          cause instanceof IngressTimeoutHandler.UpstreamResponseTimeoutException
              ? HttpResponseStatus.GATEWAY_TIMEOUT
              : HttpResponseStatus.BAD_GATEWAY);
      return;
    }
    completeAndClose(ctx);
  }

  private static void completeAndClose(ChannelHandlerContext ctx) {
    ctx.channel().close();
  }

  private static void completeWithError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }
}
