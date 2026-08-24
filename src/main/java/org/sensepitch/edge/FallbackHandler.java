package org.sensepitch.edge;

import static org.sensepitch.edge.FallbackConfig.DEFAULT_CONTENT_TYPE;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

/// Replaces upstream 500/503 responses with an operator-configured fallback page (or text). Handles
/// both the aggregated [FullHttpResponse] (stub upstream) and the streamed form a real backend
/// produces ([HttpResponse] head + [HttpContent] chunks + [LastHttpContent]).
///
/// @author Raid Thabet
public class FallbackHandler extends ChannelOutboundHandlerAdapter {

  /// `true` while the origin body chunks of a streamed 5xx response are being dropped after its
  /// head was replaced with the fallback.
  private boolean suppressing;

  private final ResponseConfig unavailable; // fallbackConfig.unavailableResponse()

  private final ResponseConfig error;

  private final byte[] unavailableContent;

  private final byte[] errorContent;

  /// @param unavailableContent body of the 503 page fallback, `null` if it is a redirect
  /// @param errorContent body of the 500 page fallback, `null` if it is a redirect
  FallbackHandler(FallbackConfig fallbackConfig, byte[] unavailableContent, byte[] errorContent) {
    this.unavailable = fallbackConfig.unavailableResponse();
    this.error = fallbackConfig.errorResponse();
    this.unavailableContent = unavailableContent;
    this.errorContent = errorContent;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {

    // Aggregated response (full http response from embedded channel when site.response is
    // configured)
    if (msg instanceof FullHttpResponse aggregated) {
      handleAggregatedResponse(ctx, aggregated, promise);
      return;
    }

    // Streamed response (real backend when site.response is not configured)
    if (msg instanceof HttpResponse head) {
      handleStreamedHead(ctx, head, promise);
      return;
    }
    if (msg instanceof HttpContent chunk) {
      handleStreamedContent(ctx, chunk, promise);
      return;
    }

    super.write(ctx, msg, promise);
  }

  private void handleAggregatedResponse(
      ChannelHandlerContext ctx, FullHttpResponse aggregated, ChannelPromise promise)
      throws Exception {
    if (!isFallbackStatus(aggregated.status().code())) {
      suppressing = false;
      super.write(ctx, aggregated, promise);
      return;
    }
    writeFallback(ctx, aggregated, promise);
  }

  private void handleStreamedHead(
      ChannelHandlerContext ctx, HttpResponse head, ChannelPromise promise) throws Exception {
    suppressing = isFallbackStatus(head.status().code());
    if (!suppressing) {
      super.write(ctx, head, promise);
      return;
    }
    writeFallback(ctx, head, promise);
  }

  private void handleStreamedContent(
      ChannelHandlerContext ctx, HttpContent chunk, ChannelPromise promise) throws Exception {
    if (!suppressing) {
      super.write(ctx, chunk, promise);
      return;
    }
    chunk.release();
    promise.setSuccess();
    if (chunk instanceof LastHttpContent) {
      suppressing = false;
    }
  }

  private void writeFallback(
      ChannelHandlerContext ctx, HttpResponse source, ChannelPromise promise) {
    FullHttpResponse fallback = buildFallback(ctx, source);
    ReferenceCountUtil.release(source);
    ctx.write(fallback, promise);
  }

  private FullHttpResponse buildFallback(ChannelHandlerContext ctx, HttpResponse source) {
    boolean serviceUnavailable =
        source.status().code() == HttpResponseStatus.SERVICE_UNAVAILABLE.code();
    ResponseConfig cfg = serviceUnavailable ? unavailable : error;
    Fallback.Redirect redirect = Fallback.resolvedRedirect(cfg.location(), cfg.status());

    if (redirect != null) {
      return buildRedirectFallback(source, redirect);
    }
    byte[] content = serviceUnavailable ? unavailableContent : errorContent;
    return buildPageFallback(ctx, source, cfg, content);
  }

  private FullHttpResponse buildRedirectFallback(HttpResponse source, Fallback.Redirect redirect) {
    FullHttpResponse redirectResponse =
        new DefaultFullHttpResponse(
            source.protocolVersion(),
            HttpResponseStatus.valueOf(redirect.status()),
            Unpooled.EMPTY_BUFFER);
    if (source.headers().contains(HttpHeaderNames.CONNECTION)) {
      copyConnectionHeaderIfPresent(source, redirectResponse);
    }
    setLocationAndLengthHeaders(redirectResponse, redirect.location());
    return redirectResponse;
  }

  private FullHttpResponse buildPageFallback(
      ChannelHandlerContext ctx, HttpResponse source, ResponseConfig cfg, byte[] content) {
    HttpResponseStatus status =
        cfg.status() != 0 ? HttpResponseStatus.valueOf(cfg.status()) : source.status();
    FullHttpResponse fallback =
        new DefaultFullHttpResponse(
            source.protocolVersion(), status, ctx.alloc().buffer().writeBytes(content));
    if (source.headers().contains(HttpHeaderNames.CONNECTION)) {
      copyConnectionHeaderIfPresent(source, fallback);
    }
    setContentTypeAndLengthHeaders(fallback, cfg, content);
    return fallback;
  }

  private boolean isFallbackStatus(int status) {
    return status == HttpResponseStatus.SERVICE_UNAVAILABLE.code()
        || status == HttpResponseStatus.INTERNAL_SERVER_ERROR.code();
  }

  private void copyConnectionHeaderIfPresent(HttpResponse source, FullHttpResponse response) {
    if (source.headers().contains(HttpHeaderNames.CONNECTION)) {
      response
          .headers()
          .set(HttpHeaderNames.CONNECTION, source.headers().get(HttpHeaderNames.CONNECTION));
    }
  }

  private void setContentTypeAndLengthHeaders(
      FullHttpResponse fallback, ResponseConfig cfg, byte[] content) {
    fallback
        .headers()
        .set(
            HttpHeaderNames.CONTENT_TYPE,
            cfg.contentType() != null ? cfg.contentType() : DEFAULT_CONTENT_TYPE);
    fallback.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
  }

  private void setLocationAndLengthHeaders(FullHttpResponse response, String location) {
    response.headers().set(HttpHeaderNames.LOCATION, location);
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
  }
}
