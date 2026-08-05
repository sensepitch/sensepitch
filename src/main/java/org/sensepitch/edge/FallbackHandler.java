package org.sensepitch.edge;

import static org.sensepitch.edge.FallbackConfig.DEFAULT_CONTENT_TYPE;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;

/**
 * Replaces upstream 500/503 responses with an operator-configured fallback page (or text). Handles
 * both the aggregated {@link FullHttpResponse} (stub upstream) and the streamed form a real backend
 * produces ({@link HttpResponse} head + {@link HttpContent} chunks + {@link LastHttpContent}).
 * @author Raid Thabet
 */
public class FallbackHandler extends ChannelOutboundHandlerAdapter {

  /**
   * {@code true} while the origin body chunks of a streamed 5xx response are being dropped after
   * its head was replaced with the fallback.
   */
  private boolean suppressing;

  private final ResponseConfig unavailable; // fallbackConfig.unavailableResponse()

  private final ResponseConfig error;

  private final byte[] unavailableContent;

  private final byte[] errorContent;

  /**
   * @param unavailableContent body of the 503 page fallback, {@code null} if it is a redirect
   * @param errorContent body of the 500 page fallback, {@code null} if it is a redirect
   */
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
    if (msg instanceof FullHttpResponse full) {
      int status = full.status().code();
      if (status == 503 || status == 500) {
        FullHttpResponse fallback = buildFallback(ctx, full);
        ReferenceCountUtil.release(full);
        ctx.write(fallback, promise);
        return;
      }
      // non-5xx aggregated response falls through and is forwarded unchanged
    }

    // Streamed response (real backend when site.response is not configured)

    // Head of a streamed response: check for 5xx and replace if needed. The following body chunks
    // will be dropped.
    if (msg instanceof HttpResponse resp) {
      int status = resp.status().code();
      boolean replace =
          status == 503
              || status == 500; // does this streamed response get swapped for the fallback?
      suppressing = replace;
      if (replace) {
        FullHttpResponse fallback = buildFallback(ctx, resp);
        ReferenceCountUtil.release(msg);
        ctx.write(fallback, promise);
        return;
      }
      // non-5xx head falls through and is forwarded unchanged
    }

    // Origin body chunks of a streamed response we already replaced: drop them.
    if (suppressing && msg instanceof HttpContent chunk) {
      chunk.release();
      promise.setSuccess();
      if (msg instanceof LastHttpContent) {
        suppressing = false;
      }
      return;
    }

    super.write(ctx, msg, promise);
  }

  private FullHttpResponse buildFallback(ChannelHandlerContext ctx, HttpResponse source) {
    boolean isDown = source.status().code() == 503;
    ResponseConfig cfg = isDown ? unavailable : error;
    Fallback.Redirect redirect = Fallback.resolvedRedirect(cfg.location(), cfg.status());

    // Redirect fallback
    if (redirect != null) {
      int code = redirect.status();
      FullHttpResponse redirectResponse =
          new DefaultFullHttpResponse(
              source.protocolVersion(), HttpResponseStatus.valueOf(code), Unpooled.EMPTY_BUFFER);
      if (source.headers().contains(HttpHeaderNames.CONNECTION)) {
        redirectResponse
            .headers()
            .set(HttpHeaderNames.CONNECTION, source.headers().get(HttpHeaderNames.CONNECTION));
      }
      redirectResponse.headers().set(HttpHeaderNames.LOCATION, redirect.location());
      redirectResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
      return redirectResponse;
    }

    // Page fallback
    byte[] content = isDown ? unavailableContent : errorContent;
    HttpResponseStatus status =
        cfg.status() != 0 ? HttpResponseStatus.valueOf(cfg.status()) : source.status();
    FullHttpResponse fallback =
        new DefaultFullHttpResponse(
            source.protocolVersion(), status, ctx.alloc().buffer().writeBytes(content));
    if (source.headers().contains(HttpHeaderNames.CONNECTION)) {
      fallback
          .headers()
          .set(HttpHeaderNames.CONNECTION, source.headers().get(HttpHeaderNames.CONNECTION));
    }
    fallback
        .headers()
        .set(
            HttpHeaderNames.CONTENT_TYPE,
            cfg.contentType() != null ? cfg.contentType() : DEFAULT_CONTENT_TYPE);
    fallback.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
    return fallback;
  }
}
