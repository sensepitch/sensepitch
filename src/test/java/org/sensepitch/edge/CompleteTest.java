package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class CompleteTest {

  static final ProxyConfig COMMON_CONFIG =
      ProxyConfig.builder()
          .listen(
              ListenConfig.builder()
                  .ssl(
                      SslConfig.builder()
                          .keyPath("classpath:ssl/test.key")
                          .certPath("classpath:ssl/test.crt")
                          .build())
                  .hosts(List.of("unserviced-domain.com"))
                  .build())
          .unservicedHost(
              UnservicedHostConfig.builder().defaultLocation("https://sensepitch.io").build())
          .protection(
              ProtectionConfig.builder()
                  .deflector(
                      DeflectorConfig.builder()
                          .tokenGenerators(
                              List.of(
                                  AdmissionTokenGeneratorConfig.builder()
                                      .prefix("X")
                                      .secret("munich")
                                      .build()))
                          .build())
                  .build())
          .sites(
              Map.of(
                  "example.com",
                  SiteConfig.builder()
                      .response(ResponseConfig.builder().text("a test response").build())
                      .protection(ProtectionConfig.builder().disable(true).build())
                      .build(),
                  "withprotection.com",
                  SiteConfig.builder()
                      .response(ResponseConfig.builder().text("a test response").build())
                      .build()))
          .metrics(MetricsConfig.builder().enable(false).build())
          .build();

  Steps steps = new Steps().given_initialized_proxy_with(COMMON_CONFIG);

  @Test
  void deflected() {
    steps
        .when_requesting("withprotection.com", "/")
        .then_the_response_status_is(HttpResponseStatus.FORBIDDEN);
  }

  @Test
  void testMissingHost() {
    steps
        .when_requesting(null, "anything")
        .then_the_response_status_is(HttpResponseStatus.BAD_REQUEST)
        .then_channel_closed();
  }

  @Test
  void testUnknownHost() {
    steps
        .when_requesting("unknown.com", "anything")
        .then_the_response_status_is(HttpResponseStatus.BAD_REQUEST)
        .then_channel_closed();
  }

  @Test
  void redirectUnserviced() {
    steps
        .when_requesting("unserviced-domain.com", "anything")
        .then_the_response_status_is(HttpResponseStatus.TEMPORARY_REDIRECT)
        .then_the_response_location_header_is("https://sensepitch.io/NOT_FOUND")
        .then_channel_closed();
    steps
        .when_requesting("unserviced-domain.com", "/")
        .then_the_response_status_is(HttpResponseStatus.TEMPORARY_REDIRECT)
        .then_the_response_location_header_is("https://sensepitch.io")
        .then_channel_closed();
  }

  @Test
  void responding() {
    steps
        .when_requesting("example.com", "/anything")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("a test response")
        .then_channel_open()
        .when_requesting("example.com", "/another")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("a test response")
        .then_channel_open();
  }

  /// Requests not matching a path go to the site default. Path upstreams are never connected here.
  @Test
  void nonMatchingPathGoesToSiteDefault() {
    PathRouteConfig unreachable =
        PathRouteConfig.builder()
            .upstream(UpstreamConfig.builder().target("127.0.0.1:1").build())
            .build();
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("site default").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .paths(Map.of("/api/*", unreachable))
                        .build()))
            .build();
    steps = new Steps().given_initialized_proxy_with(config);
    steps
        .when_requesting("example.com", "/products")
        .then_expect_content("site default")
        .when_requesting("example.com", "/apix")
        .then_expect_content("site default")
        .then_channel_open();
  }

  @Test
  void pathWithFixedResponse() {
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("site default").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .paths(
                            Map.of(
                                "/bo/*",
                                PathRouteConfig.builder()
                                    .response(
                                        ResponseConfig.builder()
                                            .status(404)
                                            .text("Not Found")
                                            .build())
                                    .build(),
                                "/bo",
                                PathRouteConfig.builder()
                                    .response(ResponseConfig.builder().text("exact").build())
                                    .build()))
                        .build()))
            .build();
    steps = new Steps().given_initialized_proxy_with(config);
    steps
        .when_requesting("example.com", "/bo/x")
        .then_the_response_status_is(HttpResponseStatus.NOT_FOUND)
        .then_expect_content("Not Found")
        .then_channel_open()
        .when_requesting("example.com", "/bo")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("exact")
        .then_channel_open()
        .when_requesting("example.com", "/books")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("site default")
        .then_channel_open()
        .when_requesting("example.com", "/products")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("site default")
        .then_channel_open();
  }

  @Test
  void pathResponseFromFile(@TempDir Path tempDir) throws IOException {
    Path pageFile = tempDir.resolve("blocked.html");
    Files.writeString(pageFile, "<html>from disk</html>");
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(
                            ResponseConfig.builder()
                                .file("classpath:fallback/unavailable_page.html")
                                .build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .paths(
                            Map.of(
                                "/blocked",
                                PathRouteConfig.builder()
                                    .response(
                                        ResponseConfig.builder()
                                            .status(403)
                                            .file(pageFile.toAbsolutePath().toString())
                                            .contentType("text/plain")
                                            .build())
                                    .build()))
                        .build()))
            .build();
    steps = new Steps().given_initialized_proxy_with(config);
    steps
        .when_requesting("example.com", "/blocked")
        .then_the_response_status_is(HttpResponseStatus.FORBIDDEN)
        .then_expect_content("<html>from disk</html>")
        .then_the_response_header_is(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        .then_channel_open()
        .when_requesting("example.com", "/other")
        .then_the_response_status_is(HttpResponseStatus.OK)
        .then_expect_content("<html>down</html>")
        .then_the_response_header_is(
            HttpHeaderNames.CONTENT_TYPE, FallbackConfig.DEFAULT_CONTENT_TYPE)
        .then_channel_open();
  }

  /// A configured 503 response is sent as configured and not replaced by the fallback page.
  @Test
  void response503FromFileIsNotReplacedByFallback(@TempDir Path tempDir) throws IOException {
    Path pageFile = tempDir.resolve("maintenance.html");
    Files.writeString(pageFile, "<html>maintenance</html>");
    ResponseConfig maintenance =
        ResponseConfig.builder().status(503).file(pageFile.toUri().toString()).build();
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(maintenance)
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .paths(
                            Map.of(
                                "/path/*",
                                PathRouteConfig.builder()
                                    .response(maintenance.toBuilder().status(500).build())
                                    .build()))
                        .build()))
            .build();
    steps = new Steps().given_initialized_proxy_with(config);
    steps
        .when_requesting("example.com", "/")
        .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
        .then_expect_content("<html>maintenance</html>")
        .then_the_response_header_is(
            HttpHeaderNames.CONTENT_TYPE, FallbackConfig.DEFAULT_CONTENT_TYPE)
        .when_requesting("example.com", "/path/x")
        .then_the_response_status_is(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        .then_expect_content("<html>maintenance</html>");
  }

  @Test
  void pathResponseWithMissingFileFailsConstruction() {
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(
                            ResponseConfig.builder().file("classpath:does/not/exist.html").build())
                        .build()))
            .build();
    assertThatThrownBy(() -> new Proxy(config))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Failed to read response file: classpath:does/not/exist.html");
  }

  @Test
  void pathResponseSetsCookie() {
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("site default").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .paths(
                            Map.of(
                                "/beta/on/*",
                                PathRouteConfig.builder()
                                    .response(
                                        ResponseConfig.builder()
                                            .location("/")
                                            .cookies(
                                                List.of(
                                                    SetCookieConfig.builder().name("beta").build()))
                                            .build())
                                    .build()))
                        .build()))
            .build();
    steps = new Steps().given_initialized_proxy_with(config);
    steps
        .when_requesting("example.com", "/beta/on/")
        .then_the_response_status_is(HttpResponseStatus.FOUND)
        .then_the_response_location_header_is("/")
        .then_the_response_header_is(
            HttpHeaderNames.SET_COOKIE, "beta=1; Path=/; Secure; HTTPOnly; SameSite=Lax")
        .then_channel_open()
        .when_requesting("example.com", "/products")
        .then_the_response_header_is(HttpHeaderNames.SET_COOKIE, null)
        .then_channel_open();
  }

  @AfterEach
  void finish() {
    steps.finish_and_check_for_leaks();
  }

  static class Steps extends ProxyConstructBDDTest.ExtendableSteps<Steps> {

    EmbeddedChannel ingressChannel;
    HttpResponse response;
    List<ReferenceCounted> referenceCounted = new ArrayList<>();

    @Step
    Steps finish_and_check_for_leaks() {
      if (ingressChannel == null) {
        return this;
      }
      for (ReferenceCounted ref : referenceCounted) {
        assertThat(ref.refCnt())
            .describedAs("Reference released, type: " + ref.getClass().getSimpleName())
            .isEqualTo(0);
      }
      ingressChannel.finishAndReleaseAll();
      return this;
    }

    @Step
    Steps given_initialized_proxy_with(ProxyConfig proxyConfig) {
      given_the_configuration(proxyConfig);
      then_expect_initialized_without_exception();
      return this;
    }

    private void newChannelIfNeeded() {
      if (ingressChannel == null || !ingressChannel.isActive()) {
        ChannelInitializer<EmbeddedChannel> channelInitializer =
            new ChannelInitializer<>() {
              @Override
              protected void initChannel(EmbeddedChannel ch) throws Exception {
                proxy.addHttpHandlers(ch.pipeline());
              }
            };
        ingressChannel = new EmbeddedChannel(channelInitializer);
      }
    }

    @Step
    Steps when_requesting(String host, String uri) {
      return when_requesting_with_cookie(host, uri, null);
    }

    @Step
    Steps when_requesting_with_cookie(String host, String uri, String cookie) {
      newChannelIfNeeded();
      DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      if (host != null) {
        req.headers().set(HttpHeaderNames.HOST, host);
      }
      if (cookie != null) {
        req.headers().set(HttpHeaderNames.COOKIE, cookie);
      }
      // TODO: add lstacontent?
      // ingressChannel.writeInbound(req);
      HttpContent emptyChunk = new DefaultHttpContent(ingressChannel.alloc().buffer(1).clear());
      referenceCounted.add(emptyChunk);
      // ingressChannel.writeInbound(emptyChunk);
      LastHttpContent emptyLastContent =
          new DefaultLastHttpContent(ingressChannel.alloc().buffer(1).clear());
      referenceCounted.add(emptyLastContent);
      ingressChannel.writeInbound(req, emptyChunk, emptyLastContent);
      Object msg = ingressChannel.readOutbound();
      assertThat(msg).isNotNull();
      assertThat(msg).isInstanceOf(FullHttpResponse.class);
      response = (HttpResponse) msg;
      return this;
    }

    @Step
    Steps then_the_response_status_is(HttpResponseStatus expectedStatus) {
      assertThat(response.status().code()).isEqualTo(expectedStatus.code());
      return this;
    }

    @Step
    Steps then_the_response_location_header_is(String location) {
      assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(location);
      return this;
    }

    @Step
    Steps then_expect_content(String expectedContent) {
      ByteBuf bb = ((HttpContent) response).content();
      String txt = bb.readString(bb.readableBytes(), StandardCharsets.US_ASCII);
      assertThat(txt).isEqualTo(expectedContent);
      return this;
    }

    @Step
    Steps then_the_response_header_is(CharSequence name, String value) {
      assertThat(response.headers().get(name)).isEqualTo(value);
      return this;
    }

    @Step
    Steps then_channel_closed() {
      assertThat(ingressChannel.isActive()).isFalse();
      return this;
    }

    @Step
    Steps then_channel_open() {
      assertThat(ingressChannel.isActive()).isTrue();
      return this;
    }
  }
}
