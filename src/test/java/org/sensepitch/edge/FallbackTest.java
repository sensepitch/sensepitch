package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_IMPLEMENTED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PERMANENT_REDIRECT;
import static io.netty.handler.codec.http.HttpResponseStatus.SEE_OTHER;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static io.netty.handler.codec.http.HttpResponseStatus.TEMPORARY_REDIRECT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.io.UncheckedIOException;
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
 * @author Raid Thabet
 */
public class FallbackTest {

  private static final String HTML = "text/html; charset=UTF-8";

  private static final FallbackConfig GLOBAL =
      FallbackConfig.builder()
          .unavailableResponse(page("global-unavailable"))
          .errorResponse(page("global-error"))
          .build();

  private EmbeddedChannel channel;
  private Object messageWritten;

  private static ResponseConfig page(String text) {
    return ResponseConfig.builder().text(text).build();
  }

  private static FallbackConfig siteUnavailable(ResponseConfig r) {
    return FallbackConfig.builder().unavailableResponse(r).build();
  }

  private static FallbackConfig siteError(ResponseConfig r) {
    return FallbackConfig.builder().errorResponse(r).build();
  }

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
    channel = new EmbeddedChannel(capture, new Fallback(cfg).newHandler());
  }

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

  private void assertPage(HttpResponseStatus status, String body, String contentType) {
    assertThat(messageWritten)
        .isInstanceOfSatisfying(
            FullHttpResponse.class,
            r -> {
              assertThat(r.status()).isEqualTo(status);
              assertThat(r.content().toString(StandardCharsets.UTF_8)).isEqualTo(body);
              assertThat(r.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(contentType);
              assertThat(r.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                  .isEqualTo(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));
            });
  }

  private void assertPage(HttpResponseStatus status, String body) {
    assertPage(status, body, HTML);
  }

  private void assertRedirect(HttpResponseStatus status, String location) {
    assertThat(messageWritten)
        .isInstanceOfSatisfying(
            FullHttpResponse.class,
            r -> {
              assertThat(r.status()).isEqualTo(status);
              assertThat(r.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(location);
              assertThat(r.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("0");
              assertThat(r.content().readableBytes()).isZero();
            });
  }

  private void assertPassThrough(HttpResponseStatus status, String body) {
    assertThat(messageWritten)
        .isInstanceOfSatisfying(
            FullHttpResponse.class,
            r -> {
              assertThat(r.status()).isEqualTo(status);
              assertThat(r.content().toString(StandardCharsets.UTF_8)).isEqualTo(body);
            });
  }

  private static FallbackConfig parse(String yaml) {
    Node root = new Yaml().compose(new StringReader(yaml));
    return RecordConstructor.construct(FallbackConfig.class, root);
  }

  @Test
  public void partialConfigParsesSparse() {
    FallbackConfig cfg =
        parse(
            """
            unavailableResponse:
                text: site2 down
            """);

    assertThat(cfg.unavailableResponse()).isNotNull();
    assertThat(cfg.unavailableResponse().text()).isEqualTo("site2 down");
    assertThat(cfg.unavailableResponse().file()).isNull();
    assertThat(cfg.unavailableResponse().location()).isNull();
    assertThat(cfg.unavailableResponse().contentType()).isNull();
    assertThat(cfg.unavailableResponse().status()).isEqualTo(0);
    assertThat(cfg.errorResponse())
        .as("slot not present in YAML must stay null, not be seeded from defaults")
        .isNull();
  }

  @Test
  public void siteOverrideDoesNotBeatGlobal() {
    FallbackConfig global = parse("errorResponse:\n  text: global error\n");
    FallbackConfig site = parse("unavailableResponse:\n  text: site2 down\n");

    FallbackConfig resolved = FallbackConfig.DEFAULTS.merge(global).merge(site);

    assertThat(resolved.unavailableResponse().text())
        .as("site override applies")
        .isEqualTo("site2 down");
    assertThat(resolved.errorResponse().text())
        .as("global error slot must survive: the site never overrode it")
        .isEqualTo("global error");
  }

  @Test
  public void redirectAndPageInSameResponseFailsToParse() {
    assertThatThrownBy(() -> parse("unavailableResponse:\n  location: /elsewhere\n  text: hi\n"))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void redirectWithNon3xxStatusFails() {
    assertThatThrownBy(() -> ResponseConfig.builder().location("/elsewhere").status(200).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("redirect status must be one of [301, 302, 303, 307, 308], was: 200");
  }

  /** 3xx, but not a code a {@code Location} header means anything for. */
  @Test
  public void redirectWithNonRedirect3xxStatusFails() {
    assertThatThrownBy(() -> ResponseConfig.builder().location("/elsewhere").status(304).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was: 304");
    assertThatThrownBy(() -> ResponseConfig.builder().location("/elsewhere").status(305).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was: 305");
  }

  @Test
  public void pageStatusOutsideHttpRangeFails() {
    assertThatThrownBy(() -> ResponseConfig.builder().text("hi").status(42).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("status must be 100..599, was: 42");
  }

  /** A page with no explicit status keeps the sentinel 0, meaning "inherit the origin status". */
  @Test
  public void pageWithoutStatusKeepsZeroSentinel() {
    assertThat(page("down").status()).isZero();
  }

  @Test
  public void redirectWithoutStatusNormalizesTo302() {
    assertThat(ResponseConfig.builder().location("/elsewhere").build().status()).isEqualTo(302);
  }

  @Test
  public void testGlobalUnavailable() {
    init(merged(null));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "global-unavailable");
  }

  @Test
  public void testGlobalError() {
    init(merged(null));
    upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
    assertPage(INTERNAL_SERVER_ERROR, "global-error");
  }

  @Test
  public void testSiteOverridesUnavailable() {
    init(merged(siteUnavailable(page("site3-unavailable"))));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "site3-unavailable");
  }

  @Test
  public void testSiteInheritsGlobalError() {
    init(merged(siteUnavailable(page("site is down"))));
    upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
    assertPage(INTERNAL_SERVER_ERROR, "global-error");
  }

  @Test
  public void testSiteTextSlotDropsDefaultFile() {
    init(FallbackConfig.DEFAULTS.merge(siteUnavailable(page("we are down"))));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    // NOT the bundled fallback/unavailable.html; the whole DEFAULTS slot (file included) was
    // replaced.
    assertPage(SERVICE_UNAVAILABLE, "we are down");
  }

  @Test
  public void testPageHonorsExplicitStatus() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder().text("down for maintenance").status(200).build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(OK, "down for maintenance");
  }

  @Test
  public void testPageWithoutStatusKeepsOriginStatus() {
    init(merged(siteUnavailable(page("still down"))));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "still down");
  }

  @Test
  public void testCustomContentType() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder()
                    .text("{\"down\":true}")
                    .contentType("application/json")
                    .build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "{\"down\":true}", "application/json");
  }

  @Test
  public void testOkPassesThrough() {
    init(merged(null));
    upstreamResponds(OK, "real-body");
    assertPassThrough(OK, "real-body");
  }

  @Test
  public void testNon5xxPassesThrough() {
    init(merged(null));
    upstreamResponds(NOT_FOUND, "real-404");
    assertPassThrough(NOT_FOUND, "real-404");
  }

  @Test
  public void testOtherServerErrorStatusesPassThroughUnmodified() {
    init(merged(null));
    upstreamResponds(NOT_IMPLEMENTED, "real-501");
    assertPassThrough(NOT_IMPLEMENTED, "real-501");
  }

  @Test
  public void testUnavailablePageFromClasspath() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder()
                    .file("classpath:fallback/unavailable_page.html")
                    .build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "<html>down</html>");
  }

  @Test
  public void testFileAndTextTogetherRejected() {
    assertThatThrownBy(
            () ->
                ResponseConfig.builder()
                    .file("classpath:fallback/unavailable_page.html")
                    .text("service is unavailable")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not both");
  }

  @Test
  public void testErrorPageFromClasspath() {
    init(
        merged(
            siteError(
                ResponseConfig.builder().file("classpath:fallback/error_page.html").build())));
    upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
    assertPage(INTERNAL_SERVER_ERROR, "<html>error</html>");
  }

  @Test
  public void testMissingFileHardFails() {
    FallbackConfig cfg =
        merged(
            siteUnavailable(
                ResponseConfig.builder()
                    .file("classpath:fallback/non_existent_page.html")
                    .build()));
    assertThatThrownBy(() -> new Fallback(cfg))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("non_existent_page.html");
  }

  @Test
  public void testSchemelessResolvesViaClasspath() {
    // no scheme, not on disk -> falls through to the classpath resource
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder().file("fallback/unavailable_page.html").build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "<html>down</html>");
  }

  @Test
  public void testSchemelessPrefersFilesystem(@TempDir Path tempDir) throws IOException {
    Path pageFile = tempDir.resolve("down.html");
    Files.writeString(pageFile, "<html>disk-first</html>");
    // no scheme, exists on disk -> filesystem wins over any classpath resource
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder().file(pageFile.toAbsolutePath().toString()).build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "<html>disk-first</html>");
  }

  @Test
  public void testUnavailablePageFromDiskFile(@TempDir Path tempDir) throws IOException {
    Path pageFile = tempDir.resolve("down.html");
    Files.writeString(pageFile, "<html>disk-down</html>");

    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder().file("file:" + pageFile.toAbsolutePath()).build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertPage(SERVICE_UNAVAILABLE, "<html>disk-down</html>");
  }

  @Test
  public void testUnavailableRedirectDefaults302() {
    init(
        merged(
            siteUnavailable(ResponseConfig.builder().location("https://status.example/").build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertRedirect(FOUND, "https://status.example/");
  }

  @Test
  public void testUnavailableRedirectHonorsExplicitStatus() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder().location("https://status.example/").status(301).build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertRedirect(MOVED_PERMANENTLY, "https://status.example/");
  }

  @Test
  public void testUnavailablePermanentRedirect() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder()
                    .location("https://elsewhere.example/")
                    .status(308)
                    .build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertRedirect(PERMANENT_REDIRECT, "https://elsewhere.example/");
  }

  @Test
  public void testErrorPermanentRedirect() {
    init(
        merged(
            siteError(
                ResponseConfig.builder()
                    .location("https://elsewhere.example/")
                    .status(308)
                    .build())));
    upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
    assertRedirect(PERMANENT_REDIRECT, "https://elsewhere.example/");
  }

  @Test
  public void testErrorTemporaryRedirect() {
    init(
        merged(
            siteError(
                ResponseConfig.builder()
                    .location("https://elsewhere.example/")
                    .status(307)
                    .build())));
    upstreamResponds(INTERNAL_SERVER_ERROR, "ignored-origin-body");
    assertRedirect(TEMPORARY_REDIRECT, "https://elsewhere.example/");
  }

  @Test
  public void testUnavailableSeeOtherRedirect() {
    init(
        merged(
            siteUnavailable(
                ResponseConfig.builder()
                    .location("https://status.example/")
                    .status(303)
                    .build())));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    assertRedirect(SEE_OTHER, "https://status.example/");
  }

  @Test
  public void testSitePageReplacesGlobalRedirect() {
    FallbackConfig globalRedirect =
        FallbackConfig.builder()
            .unavailableResponse(
                ResponseConfig.builder()
                    .location("http://origin.example/data")
                    .status(308)
                    .build())
            .build();
    FallbackConfig site = siteUnavailable(page("SITE3: down for maintenance"));

    init(FallbackConfig.DEFAULTS.merge(globalRedirect).merge(site));
    upstreamResponds(SERVICE_UNAVAILABLE, "ignored-origin-body");
    // page served, no Location header logic involved; the global redirect is gone entirely
    assertPage(SERVICE_UNAVAILABLE, "SITE3: down for maintenance");
  }

  /**
   * An empty slot has neither a redirect target nor a page body. This is now rejected when the
   * record is constructed, so such a slot can never reach {@link Fallback} to be merged at all.
   */
  @Test
  public void testEmptySlotHardFails() {
    assertThatThrownBy(() -> ResponseConfig.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response should be one of");
  }

  @Test
  public void redirectAndPageInSameResponseRejected() {
    assertThatThrownBy(
            () -> ResponseConfig.builder().location("/elsewhere").text("we are down").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response should be one of");
  }
}
