package org.sensepitch.edge;

import static org.sensepitch.edge.FallbackConfig.DEFAULT_CONTENT_TYPE;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replaces upstream 500/503 responses with an operator-configured fallback page (or text). Handles
 * both the aggregated {@link FullHttpResponse} (stub upstream) and the streamed form a real backend
 * produces ({@link HttpResponse} head + {@link HttpContent} chunks + {@link LastHttpContent}).
 *
 * @author Raid Thabet
 */
@ChannelHandler.Sharable
public class FallbackHandler extends ChannelOutboundHandlerAdapter {

  static ProxyLogger LOG = ProxyLogger.get(FallbackHandler.class);

  /**
   * Per-connection flag: {@code TRUE} while the origin body chunks of a streamed 5xx response are
   * being dropped after its head was replaced with the fallback. Stored on the channel (not as an
   * instance field) so the handler can remain {@link ChannelHandler.Sharable @Sharable}.
   */
  private static final AttributeKey<Boolean> SUPPRESSING =
      AttributeKey.valueOf(FallbackHandler.class, "suppressing");

  private final ResponseConfig unavailable; // fallbackConfig.unavailableResponse()

  private final ResponseConfig error;

  private final byte[] unavailableContent;

  private final byte[] errorContent;

  public FallbackHandler(FallbackConfig fallbackConfig) {
    this.unavailable = fallbackConfig.unavailableResponse();
    this.error = fallbackConfig.errorResponse();

    unavailableContent = loadOrDefault(this.unavailable.file(), this.unavailable.text());
    errorContent = loadOrDefault(this.error.file(), this.error.text());
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

    Attribute<Boolean> suppressing = ctx.channel().attr(SUPPRESSING);

    // Head of a streamed response: check for 5xx and replace if needed. The following body chunks
    // will be dropped.
    if (msg instanceof HttpResponse resp) {
      int status = resp.status().code();
      boolean replace =
          status == 503
              || status == 500; // does this streamed response get swapped for the fallback?
      suppressing.set(replace);
      if (replace) {
        FullHttpResponse fallback = buildFallback(ctx, resp);
        ReferenceCountUtil.release(msg);
        ctx.write(fallback, promise);
        return;
      }
      // non-5xx head falls through and is forwarded unchanged
    }

    // Origin body chunks of a streamed response we already replaced: drop them.
    if (Boolean.TRUE.equals(suppressing.get()) && msg instanceof HttpContent chunk) {
      chunk.release();
      promise.setSuccess();
      if (msg instanceof LastHttpContent) {
        suppressing.set(Boolean.FALSE);
      }
      return;
    }

    super.write(ctx, msg, promise);
  }

  private FullHttpResponse buildFallback(ChannelHandlerContext ctx, HttpResponse source) {
    boolean isDown = source.status().code() == 503;
    ResponseConfig cfg = isDown ? unavailable : error;
    ResponseConfig.Redirect redirect = cfg.resolvedRedirect();

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

  /**
   * Load an HTML page for the fallback response. Tries, in order:
   *
   * <ol>
   *   <li>a file on disk at {@code path} (operator-provided override)
   *   <li>a classpath resource at {@code path} (bundled default page)
   *   <li>{@code defaultText} as plain UTF-8 (last-resort)
   * </ol>
   */
  private static byte[] loadOrDefault(String path, String defaultText) {
    // in case a file is provided
    if (path != null) {
      try {
        Path filePath = Path.of(path);
        if (Files.isReadable(filePath)) {
          return Files.readAllBytes(filePath);
        }
      } catch (IOException e) {
        LOG.error("Failed to read fallback page from file: " + path, e);
      }

      try (InputStream in = FallbackHandler.class.getClassLoader().getResourceAsStream(path)) {
        if (in != null) {
          return in.readAllBytes();
        }
      } catch (IOException e) {
        LOG.error("Failed to read fallback page from classpath: " + path, e);
      }
    }

    // in case a file is not provided or not found
    String text = defaultText != null ? defaultText : "Unknown problem occurred";
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
