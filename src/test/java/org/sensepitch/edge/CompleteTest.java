package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @author Jens Wilke
 */
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
      newChannelIfNeeded();
      DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      if (host != null) {
        req.headers().set(HttpHeaderNames.HOST, host);
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
