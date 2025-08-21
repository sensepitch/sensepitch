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
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.net.SocketException;
import javax.net.ssl.SSLHandshakeException;

/**
 * @author Jens Wilke
 */
public class ExceptionHandler extends ChannelInboundHandlerAdapter {

  static final ProxyLogger LOG = ProxyLogger.get(ExceptionHandler.class);

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

  /** True for all variants of common ssl decoder exceptions */
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

  /** True for all known variants of connection reset. */
  boolean isConnectionReset(Throwable cause) {
    return cause instanceof SocketException
        && cause.getMessage() != null
        && cause.getMessage().startsWith("Connection reset");
  }

  /** Handle an exception, this might be a connection reset a timeout etc. */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    // connection reset might include the IP address, not good
    boolean connectionReset = isConnectionReset(cause);
    if (!sslHandshakeComplete) {
      String phase = "handshake";
      if (isSslDecoderException(cause)) {
        metrics.ingressErrorCounter.labelValues(phase, "ssl").inc();
      } else if (connectionReset) {
        metrics.ingressErrorCounter.labelValues(phase, "reset").inc();
      } else {
        metrics.ingressErrorCounter.labelValues(phase, "other").inc();
        LOG.downstreamError(ctx.channel(), "handshake error", cause);
      }
      completeAndClose(ctx);
      return;
    }
    // logging handler does know exact state
    RequestLoggingHandler loggingHandler =
        ctx.channel().pipeline().get(RequestLoggingHandler.class);
    String phase;
    if (!loggingHandler.isRequestReceived()) {
      phase = "request";
    } else if (!loggingHandler.isRequestComplete()) {
      phase = "upload";
    } else if (!loggingHandler.isResponseStarted()) {
      phase = "waiting";
    } else {
      phase = "responding";
    }
    // for connection reset we don't log or respond, just count
    if (connectionReset) {
      metrics.ingressErrorCounter.labelValues(phase, "reset").inc();
      completeAndClose(ctx);
      return;
    }
    metrics.ingressErrorCounter.labelValues(phase, "other").inc();
    // send a proper response if possible, log via standard request log
    if (loggingHandler.isRequestReceived() && !loggingHandler.isResponseStarted()) {
      // TODO: timeout?
      // TODO: sanitize status after logging in write path?
      HttpResponseStatus status = new HttpResponseStatus(502, cause.toString());
      completeWithError(ctx, status);
      return;
    }
    // log if impossible to send response
    LOG.downstreamError(ctx.channel(), "error, phase=" + phase, cause);
    completeAndClose(ctx);
  }

  private static void completeAndClose(ChannelHandlerContext ctx) {
    DownstreamProgress.complete(ctx.channel());
    ctx.channel().close();
  }

  private static void completeWithError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    DownstreamProgress.complete(ctx.channel());
  }
}
