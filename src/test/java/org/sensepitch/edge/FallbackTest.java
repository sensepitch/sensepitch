package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Tests {@link FallbackHandler} against the aggregated {@link FullHttpResponse} shape (as the
 * in-process {@code response:} stub produces); the streamed shape is covered in
 * {@link FallbackStreamingTest}. Each test uses the config {@code SiteSelector} would build
 * (DEFAULT overridden by global overridden by site) so the layering is exercised too.
 *
 * @author Raid Thabet
 */
public class FallbackTest {

    private static final FallbackConfig GLOBAL =
            FallbackConfig.builder()
                    .unavailableText("global-unavailable") // 18 bytes
                    .errorText("global-error") // 12 bytes
                    .build();

    private EmbeddedChannel channel;
    private Object messageWritten;

    /**
     * Mirrors {@code SiteSelector}: DEFAULT overridden by global, overridden by the site.
     */
    private static FallbackConfig merged(FallbackConfig site) {
        return FallbackConfig.DEFAULTS.merge(GLOBAL).merge(site);
    }

    private void init(FallbackConfig cfg) {
        ChannelOutboundHandler capture =
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        messageWritten = msg;
                        ctx.write(msg, promise);
                    }
                };
        channel = new EmbeddedChannel(capture, new FallbackHandler(cfg));
    }

    /**
     * Sends one aggregated upstream response of the given status through the handler.
     */
    private void upstreamResponds(HttpResponseStatus status, String body) {
        messageWritten = null;
        channel.writeOutbound(
                new DefaultFullHttpResponse(
                        HTTP_1_1, status, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)));
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    // --- config parse + merge: root cause and regression for the DEFAULT-seeding clobber ---

    private static FallbackConfig parse(String yaml) {
        Node root = new Yaml().compose(new StringReader(yaml));
        return RecordConstructor.construct(FallbackConfig.class, root);
    }

    /** A partial config must parse sparse: fields the YAML never set stay null, not seeded from defaults. */
    @Test
    public void partialConfigParsesSparse() {
        FallbackConfig cfg = parse("unavailableText: site2 down\n");

        assertThat(cfg.unavailableText()).isEqualTo("site2 down");
        assertThat(cfg.errorText())
                .as("field not present in YAML must stay null, not be seeded from defaults")
                .isNull();
        assertThat(cfg.errorPage()).isNull();
        assertThat(cfg.unavailablePage()).isNull();
    }

    /** A site overriding one field must not wipe a global override on another. */
    @Test
    public void siteOverrideDoesNotClobberGlobal() {
        FallbackConfig global = parse("errorText: global error\n");
        FallbackConfig site = parse("unavailableText: site2 down\n");

        // Mirrors SiteSelector.constructFallbackSupplier resolution order.
        FallbackConfig resolved = FallbackConfig.DEFAULTS.merge(global).merge(site);

        assertThat(resolved.unavailablePage()).isEqualTo("fallback/unavailable.html");
        assertThat(resolved.errorPage()).isEqualTo("fallback/error.html");
        assertThat(resolved.unavailableText()).as("site override applies").isEqualTo("site2 down");
        assertThat(resolved.errorText())
                .as("global error text must survive: the site never overrode it")
                .isEqualTo("global error");
    }

    @Test
    public void testGlobalUnavailable() {
        init(merged(null));
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.status()).isEqualTo(SERVICE_UNAVAILABLE);
                            assertThat(response.content().toString(StandardCharsets.UTF_8))
                                    .isEqualTo("global-unavailable");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                                    .isEqualTo("text/html; charset=UTF-8");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("18");
                        });
    }

    @Test
    public void testGlobalError() {
        init(merged(null));
        upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.status()).isEqualTo(INTERNAL_SERVER_ERROR);
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("global-error");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("12");
                        });
    }

    @Test
    public void testSiteOverridesUnavailableText() {
        init(merged(FallbackConfig.builder().unavailableText("site3-unavailable").build()));
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8))
                                    .isEqualTo("site3-unavailable");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("17");
                        });
    }

    @Test
    public void testOkPassesThrough() {
        init(merged(null));
        upstreamResponds(OK, "real-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.status()).isEqualTo(OK);
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("real-body");
                        });
    }

    @Test
    public void testNon5xxPassesThrough() {
        init(merged(null));
        upstreamResponds(NOT_FOUND, "real-404");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.status()).isEqualTo(NOT_FOUND);
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("real-404");
                        });
    }

    @Test
    public void testUnavailablePageFromClasspath() {
        init(merged(FallbackConfig.builder()
                        .unavailablePage("fallback/unavailable_page.html")
                        .unavailableText("service is unavailable")
                        .build()
                )
        );
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("<html>down</html>");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                                    .isEqualTo("text/html; charset=UTF-8");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("17");
                        });
    }

    @Test
    public void testUnavailablePageNotFoundFallsBackToGlobalText() {
        init(merged(FallbackConfig.builder().unavailablePage("fallback/non_existent_page.html").build()));
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("global-unavailable");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("18");
                        });
    }

    @Test
    public void testUnavailablePageNotFoundFallsBackToSiteText() {
        init(merged(FallbackConfig.builder()
                        .unavailablePage("fallback/non_existent_page.html")
                        .unavailableText("service unavailable")
                        .build()
                )
        );
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("service unavailable");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("19");
                        });
    }

    @Test
    public void testErrorPageFromClasspath() {
        init(merged(FallbackConfig.builder()
                        .errorPage("fallback/error_page.html")
                        .errorText("internal server error")
                        .build()
                )
        );
        upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("<html>error</html>");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("18");
                        });
    }

    @Test
    public void testUnavailablePageFromDiskFile(@TempDir Path tempDir) throws IOException {
        Path page = tempDir.resolve("down.html");
        Files.writeString(page, "<html>disk-down</html>");

        init(merged(FallbackConfig.builder()
                        .unavailablePage(page.toAbsolutePath().toString())
                        .unavailableText("service is unavailable")
                        .build()
                )
        );
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("<html>disk-down</html>");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("22");
                        });
    }

    @Test
    public void testSiteUnavailableText() {
        init(merged(FallbackConfig.builder().unavailableText("service is unavailable").build()));
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("service is unavailable");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("22");
                        });
    }

    @Test
    public void testSiteErrorText() {
        init(merged(FallbackConfig.builder().errorText("internal server error").build()));
        upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("internal server error");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("21");
                        });
    }

    @Test
    public void testUnavailablePageBeatsText() {
        init(
                merged(
                        FallbackConfig.builder()
                                .unavailablePage("fallback/unavailable_page.html")
                                .unavailableText("service is unavailable")
                                .build()));
        upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("<html>down</html>");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("17");
                        });
    }

    @Test
    public void testErrorPageBeatsText() {
        init(
                merged(
                        FallbackConfig.builder()
                                .errorPage("fallback/error_page.html")
                                .errorText("internal server error")
                                .build()));
        upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("<html>error</html>");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("18");
                        });
    }

    @Test
    public void testSiteInheritsGlobalError() {
        init(merged(FallbackConfig.builder().unavailableText("service is unavailable").build()));
        upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
        assertThat(messageWritten)
                .isInstanceOfSatisfying(
                        FullHttpResponse.class,
                        response -> {
                            assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("global-error");
                            assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("12");
                        });
    }
}
