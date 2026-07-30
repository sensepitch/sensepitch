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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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

  private static final String CLASSPATH_PREFIX = "classpath:";

  private static final String FILE_PREFIX = "file:";

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

    // don't load a body for redirects at all
    unavailableContent = unavailable.resolvedRedirect() != null ? null : pageBody(this.unavailable);
    errorContent = error.resolvedRedirect() != null ? null : pageBody(this.error);
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
   * Build the page body for a page (non-redirect) {@code cfg}: the resource named by {@link
   * ResponseConfig#file()} (see {@link #openResource} for how the location is resolved), or else
   * the inline {@link ResponseConfig#text()} as UTF-8. {@link ResponseConfig} already rejects
   * setting both, and the constructor skips redirects, so this is reached only for page configs.
   *
   * @throws IllegalArgumentException if {@code cfg} has neither {@code file} nor {@code text} (an
   *     empty page config)
   */
  private static byte[] pageBody(ResponseConfig cfg) {
    if (cfg.file() != null) {
      return readResource(cfg.file());
    }
    if (cfg.text() != null) {
      return cfg.text().getBytes(StandardCharsets.UTF_8);
    }
    throw new IllegalArgumentException("page config has neither file nor text");
  }

  /**
   * Read the resource fully; an unresolvable location is a configuration error that fails
   * construction.
   */
  private static byte[] readResource(String file) {
    try (InputStream in = openResource(file)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read fallback page: " + file, e);
    }
  }

  /**
   * Open the resource named by {@code location}. An explicit {@code classpath:} or {@code file:}
   * scheme picks exactly that source; a bare (schemeless) location is tried on the filesystem first
   * and then on the classpath.
   */
  private static InputStream openResource(String location) throws IOException {
    if (location.startsWith(CLASSPATH_PREFIX)) {
      return openClasspath(location.substring(CLASSPATH_PREFIX.length()));
    }
    if (location.startsWith(FILE_PREFIX)) {
      return new FileInputStream(location.substring(FILE_PREFIX.length()));
    }
    // No scheme: try the filesystem first, then the classpath.
    Path filePath = Path.of(location);
    if (Files.isReadable(filePath)) {
      return Files.newInputStream(filePath);
    }
    return openClasspath(location);
  }

  private static InputStream openClasspath(String path) throws IOException {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    InputStream in = FallbackHandler.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new FileNotFoundException("Classpath resource not found: " + path);
    }
    return in;
  }
}
