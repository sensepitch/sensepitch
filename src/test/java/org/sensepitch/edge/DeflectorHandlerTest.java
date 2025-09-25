package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class DeflectorHandlerTest {

  private DeflectorHandler handler;
  private Channel channel;
  private boolean passed;
  private Object messageWritten;

  @Test
  public void test() throws Exception {
    DeflectorConfig cfg =
        DeflectorConfig.builder()
            .serverIpv4Address("127.0.0.1")
            .bypass(BypassConfig.builder().uris(List.of("/bypass")).build())
            .noBypass(
                NoBypassConfig.builder().uris(List.of("/neverBypass", "/bypass/excluded")).build())
            .tokenGenerators(
                List.of(AdmissionTokenGeneratorConfig.builder().secret("asdf").prefix("X").build()))
            .build();
    init(cfg);
    request("/bypass");
    assertThat(passed).isTrue();
    request("/nobypass");
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
    request("/bypass/excluded");
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
    request("/nobypass", "Twitterbot/1.0");
    assertThat(passed).isTrue();
  }

  private void expectResponseIsChallenge() {
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(403);
            });
  }

  private void init(DeflectorConfig cfg) {
    ChannelOutboundHandler out =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
              throws Exception {
            // System.out.println("AdmissionHandler write " + msg);
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            // System.out.println("AdmissionHandler read " + msg);
            super.channelRead(ctx, msg);
          }
        };
    handler = new DeflectorHandler(new Deflector(cfg));
    channel = new EmbeddedChannel(out, handler, in);
  }

  void request(String uri, String userAgent) throws Exception {
    final DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    req.headers().add("User-Agent", userAgent);
    request(req);
  }

  void request(String uri) throws Exception {
    request(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
  }

  void request(HttpRequest req) throws Exception {
    ChannelHandlerContext ctx = channel.pipeline().context(DeflectorHandler.class);
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
