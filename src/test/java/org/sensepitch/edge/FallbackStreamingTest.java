package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
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
 * as opposed to the single {@link io.netty.handler.codec.http.FullHttpResponse} covered in
 * {@link FallbackTest}.
 *
 * @author Raid Thabet
 */
public class FallbackStreamingTest {

    private static final FallbackConfig CONFIG =
            FallbackConfig.builder()
                    .unavailableResponse(ResponseConfig.builder().text("global-unavailable").build()) // 18 bytes
                    .errorResponse(ResponseConfig.builder().text("global-error").build()) // 12 bytes
                    .build();

    private EmbeddedChannel channel;
    private final List<Object> written = new ArrayList<>();

    private static ChannelOutboundHandler capture(List<Object> sink) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                sink.add(msg);
                ctx.write(msg, promise);
            }
        };
    }

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(capture(written), new FallbackHandler(CONFIG));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private HttpResponse response() {
        for (Object o : written) {
            if (o instanceof HttpResponse r) {
                return r;
            }
        }
        return null;
    }

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
        assertThat(response()).isNotNull();
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
        assertThat(response()).isNotNull();
        assertThat(response().status()).isEqualTo(INTERNAL_SERVER_ERROR);
        assertThat(response().headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/html; charset=UTF-8");
        assertThat(response().headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("12");
    }

    @Test
    public void testPageHonorsExplicitStatus() {
        FallbackConfig cfg =
                FallbackConfig.builder()
                        .unavailableResponse(
                                ResponseConfig.builder().text("down page").status(200).build())
                        .errorResponse(ResponseConfig.builder().text("err").build())
                        .build();
        List<Object> out = new ArrayList<>();
        EmbeddedChannel ch = new EmbeddedChannel(capture(out), new FallbackHandler(cfg));

        ch.writeOutbound(
                new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE),
                chunk("origin-body"),
                LastHttpContent.EMPTY_LAST_CONTENT);

        HttpResponse head = null;
        for (Object o : out) {
            if (o instanceof HttpResponse r) {
                head = r;
                break;
            }
        }
        assertThat(head).isNotNull();
        assertThat(head.status()).isEqualTo(OK);

        StringBuilder sb = new StringBuilder();
        for (Object o : out) {
            if (o instanceof HttpContent c) {
                sb.append(c.content().toString(StandardCharsets.UTF_8));
            }
        }
        assertThat(sb.toString()).isEqualTo("down page");

        ch.finishAndReleaseAll();
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

        assertThat(response()).isNotNull();
        assertThat(response().status()).isEqualTo(OK);
        assertThat(body()).isEqualTo("real-body"); // forwarded unchanged, fallback NOT applied
    }

    @Test
    public void testHeadThenEmptyLastUsesFallback() {
        // 5xx head with no separate content chunk, terminated straight away
        channel.writeOutbound(
                new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE), LastHttpContent.EMPTY_LAST_CONTENT);

        assertThat(body()).isEqualTo("global-unavailable");
        assertThat(response()).isNotNull();
        assertThat(response().status()).isEqualTo(SERVICE_UNAVAILABLE);
    }

    @Test
    public void testPreservesConnectionCloseHeader() {
        DefaultHttpResponse head = new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE);
        head.headers().set(HttpHeaderNames.CONNECTION, "close");

        channel.writeOutbound(head, LastHttpContent.EMPTY_LAST_CONTENT);

        assertThat(response()).isNotNull();
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
        written.clear();
        channel.writeOutbound(
                new DefaultHttpResponse(HTTP_1_1, OK), chunk("real-2"), LastHttpContent.EMPTY_LAST_CONTENT);
        assertThat(response()).isNotNull();
        assertThat(response().status()).isEqualTo(OK);
        assertThat(body()).isEqualTo("real-2");
    }

    /** A streamed 5xx whose slot is a redirect: the head becomes a 3xx and the origin body is swallowed. */
    @Test
    public void testStreamedRedirectSwallowsBody() {
        FallbackConfig redirectCfg =
                FallbackConfig.builder()
                        .unavailableResponse(
                                ResponseConfig.builder().temporaryRedirect("https://status.example/").build())
                        .errorResponse(ResponseConfig.builder().text("err").build())
                        .build();
        List<Object> out = new ArrayList<>();
        EmbeddedChannel ch = new EmbeddedChannel(capture(out), new FallbackHandler(redirectCfg));

        DefaultHttpContent originChunk = chunk("origin-body");
        ch.writeOutbound(
                new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE),
                originChunk,
                LastHttpContent.EMPTY_LAST_CONTENT);

        HttpResponse head = null;
        for (Object o : out) {
            if (o instanceof HttpResponse r) {
                head = r;
                break;
            }
        }
        assertThat(head).isNotNull();
        assertThat(head.status()).isEqualTo(TEMPORARY_REDIRECT);
        assertThat(head.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("https://status.example/");
        assertThat(head.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("0");
        assertThat(originChunk.refCnt()).isZero(); // origin body dropped despite the redirect swap

        ch.finishAndReleaseAll();
    }
    
    @Test
    public void testStreamedRedirectPreservesConnectionCloseHeader() {
        FallbackConfig redirectCfg =
                FallbackConfig.builder()
                        .unavailableResponse(
                                ResponseConfig.builder().temporaryRedirect("https://status.example/").build())
                        .build();
        List<Object> out = new ArrayList<>();
        EmbeddedChannel ch = new EmbeddedChannel(capture(out), new FallbackHandler(FallbackConfig.DEFAULTS.merge(redirectCfg)));

        DefaultHttpResponse head = new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE);
        head.headers().set(HttpHeaderNames.CONNECTION, "close");
        ch.writeOutbound(head, LastHttpContent.EMPTY_LAST_CONTENT);

        HttpResponse response = null;
        for (Object o : out) {
            if (o instanceof HttpResponse r) {
                response = r;
                break;
            }
        }
        
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TEMPORARY_REDIRECT);
        assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");

        ch.finishAndReleaseAll();
    }
    
}
