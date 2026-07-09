package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FallbackHandler} against the streamed response shape a real upstream produces:
 * an {@link HttpResponse} head, then {@link HttpContent} chunks, then a {@link LastHttpContent},
 * as opposed to the single {@link FullHttpResponse} covered in {@link FallbackTest}.
 *
 * @author Raid Thabet
 */
public class FallbackStreamingTest {

  private static final FallbackConfig CONFIG =
      FallbackConfig.builder()
          .unavailableText("global-unavailable") // 18 bytes
          .errorText("global-error") // 12 bytes
          .build();

  private EmbeddedChannel channel;
  private final List<Object> written = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ChannelOutboundHandler capture =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            written.add(msg);
            ctx.write(msg, promise);
          }
        };
    channel = new EmbeddedChannel(capture, new FallbackHandler(CONFIG));
  }

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  /** The first response object the handler wrote downstream (head or aggregated fallback). */
  private HttpResponse response() {
    for (Object o : written) {
      if (o instanceof HttpResponse r) {
        return r;
      }
    }
    return null;
  }

  /** The concatenated body of everything the handler wrote downstream. */
  private String body() {
    StringBuilder sb = new StringBuilder();
    for (Object o : written) {
      if (o instanceof HttpContent c) {
        sb.append(c.content().toString(StandardCharsets.UTF_8));
      }
    }
    return sb.toString();
  }

  private static DefaultHttpContent chunk(String text) {
    return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
  }

  @Test
  public void testUnavailableUsesFallback() {
    DefaultHttpContent originChunk = chunk("origin-body"); // kept to assert it is released
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE),
        originChunk,
        LastHttpContent.EMPTY_LAST_CONTENT);

    assertThat(body()).isEqualTo("global-unavailable");
    assertThat(response().status()).isEqualTo(SERVICE_UNAVAILABLE);
    assertThat(response().headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/html; charset=UTF-8");
    assertThat(response().headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("18");
    assertThat(originChunk.refCnt()).isZero(); // origin body dropped and released by the handler
  }

  @Test
  public void testErrorUsesFallback() {
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR),
        chunk("origin-body"),
        LastHttpContent.EMPTY_LAST_CONTENT);

    assertThat(body()).isEqualTo("global-error");
    assertThat(response().status()).isEqualTo(INTERNAL_SERVER_ERROR);
    assertThat(response().headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/html; charset=UTF-8");
    assertThat(response().headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("12");
  }

  @Test
  public void testMultiChunkBodyFullySwallowed() {
    DefaultHttpContent c1 = chunk("chunk-1");
    DefaultHttpContent c2 = chunk("chunk-2");
    DefaultLastHttpContent last =
        new DefaultLastHttpContent(Unpooled.copiedBuffer("chunk-3", StandardCharsets.UTF_8));

    channel.writeOutbound(new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE), c1, c2, last);

    // only the fallback body is emitted, exactly once; none of the origin chunks leak through
    assertThat(body()).isEqualTo("global-unavailable");
    assertThat(c1.refCnt()).isZero();
    assertThat(c2.refCnt()).isZero();
    assertThat(last.refCnt()).isZero();
  }

  @Test
  public void testNon5xxPassesThrough() {
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, OK), chunk("real-body"), LastHttpContent.EMPTY_LAST_CONTENT);

    assertThat(response().status()).isEqualTo(OK);
    assertThat(body()).isEqualTo("real-body"); // forwarded unchanged, fallback NOT applied
  }

  @Test
  public void testHeadThenEmptyLastUsesFallback() {
    // 5xx head with no separate content chunk, terminated straight away
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE), LastHttpContent.EMPTY_LAST_CONTENT);

    assertThat(body()).isEqualTo("global-unavailable");
    assertThat(response().status()).isEqualTo(SERVICE_UNAVAILABLE);
  }

  @Test
  public void testPreservesConnectionCloseHeader() {
    DefaultHttpResponse head = new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE);
    head.headers().set(HttpHeaderNames.CONNECTION, "close");

    channel.writeOutbound(head, LastHttpContent.EMPTY_LAST_CONTENT);

    assertThat(response().headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
  }

  @Test
  public void testKeepAliveResetsFlagBetweenResponses() {
    // response 1 on the connection: streamed 503 -> replaced with the fallback
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE),
        chunk("origin-1"),
        LastHttpContent.EMPTY_LAST_CONTENT);
    assertThat(body()).isEqualTo("global-unavailable");

    // response 2 on the SAME connection: streamed 200 -> must pass through untouched.
    // If the suppression flag were not reset per head, this body would be swallowed.
    written.clear();
    channel.writeOutbound(
        new DefaultHttpResponse(HTTP_1_1, OK), chunk("real-2"), LastHttpContent.EMPTY_LAST_CONTENT);
    assertThat(response().status()).isEqualTo(OK);
    assertThat(body()).isEqualTo("real-2");
  }
}
