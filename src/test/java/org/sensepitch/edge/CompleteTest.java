package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Jens Wilke
 */
@ExtendWith(SerenityJUnit5Extension.class)
class CompleteTest {

  static final ProxyConfig COMMON_CONFIG = ProxyConfig.builder()
    .listen(ListenConfig.builder()
      .ssl(SslConfig.builder()
        .keyPath("classpath:ssl/test.key")
        .certPath("classpath:ssl/test.crt")
        .build())
      .build())
    .unservicedHost(UnservicedHostConfig.builder()
      .defaultLocation("https://sensepitch.io")
      .build())
    .sites(Map.of(
      "example.com", SiteConfig.builder()
        .responseText("a test response")
        .protection(ProtectionConfig.builder()
          .enabled(false)
          .build())
        .build()
    ))
    .metrics(MetricsConfig.builder()
      .enable(false)
      .build())
    .build();

  Steps steps = new Steps().given_the_configuration(COMMON_CONFIG);

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

  static class Steps extends ProxyConstructBDDTest.ExtendableSteps<Steps> {

    EmbeddedChannel ingressChannel;
    HttpResponse response;

    @Override
    @Step
    Steps given_the_configuration(ProxyConfig proxyConfig) {
      super.given_the_configuration(proxyConfig);
      ChannelInitializer<EmbeddedChannel> channelInitializer = new ChannelInitializer<>() {
        @Override
        protected void initChannel(EmbeddedChannel ch) throws Exception {
          proxy.addHttpHandlers(ch.pipeline());
        }
      };
      ingressChannel = new EmbeddedChannel(channelInitializer);
      return this;
    }

    @Step
    Steps when_requesting(String host, String uri) {
      DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      if (host != null) {
        req.headers().set(HttpHeaderNames.HOST, host);
      }
      ingressChannel.writeInbound(req);
      Object msg  = ingressChannel.readOutbound();
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
