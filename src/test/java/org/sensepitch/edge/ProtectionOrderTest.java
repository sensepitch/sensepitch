package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class ProtectionOrderTest {

  private static final String COOKIE_NAME = "edge-access";
  private static final String ACCESS_URI = "/gate";

  private ProtectionHandler handler;
  private Channel channel;
  private boolean passed;
  private Object messageWritten;

  @Test
  public void testCookieGateRunsBeforeDeflector() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .deflector(deflectorConfig())
            .cookieGates(List.of(CookieGateConfig.builder().name(COOKIE_NAME).accessUri(ACCESS_URI).build()))
            .build();
    Supplier<ChannelHandler> supplier = Protection.handlerSupplier(protection);
    assertThat(supplier).isNotNull();
    init(supplier.get());
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ACCESS_URI);
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            FullHttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(200);
              assertThat(response.headers().get(HttpHeaderNames.SET_COOKIE)).isNotBlank();
              assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo("welcome");
            });
  }

  private static DeflectorConfig deflectorConfig() {
    return DeflectorConfig.builder()
        .hashTargetPrefix("8")
        .serverIpv4Address("127.0.0.1")
        .bypass(BypassConfig.builder().uris(List.of("/bypass")).build())
        .noBypass(NoBypassConfig.builder().uris(List.of("/neverbypass")).build())
        .tokenGenerators(
            List.of(AdmissionTokenGeneratorConfig.builder().secret("asdf").prefix("X").build()))
        .build();
  }

  private void init(ChannelHandler protectionHandler) {
    ChannelOutboundHandler out =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
              throws Exception {
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            super.channelRead(ctx, msg);
          }
        };
    handler = (ProtectionHandler) protectionHandler;
    channel = new EmbeddedChannel(out, handler, in);
  }

  private void request(HttpRequest req) throws Exception {
    ChannelHandlerContext ctx = channel.pipeline().context(ProtectionHandler.class);
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
