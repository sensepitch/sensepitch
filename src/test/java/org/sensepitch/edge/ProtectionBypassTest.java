package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
public class ProtectionBypassTest {

  private static final String COOKIE_NAME = "edge-access";
  private static final String ACCESS_URI = "/gate";

  private ProtectionHandler handler;
  private Channel channel;
  private boolean passed;
  private Object messageWritten;

  @Test
  public void testPathBypassSkipsProtection() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .bypass(new ProtectionBypassConfig(List.of(ACCESS_URI), null))
            .cookieGates(
                List.of(CookieGateConfig.builder().name(COOKIE_NAME).accessUri(ACCESS_URI).build()))
            .build();
    Supplier<ChannelHandler> supplier = Protection.handlerSupplier(protection, new ProxyMetrics());
    assertThat(supplier).isNotNull();
    init(supplier.get());
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ACCESS_URI);
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isTrue();
    assertThat(messageWritten).isNull();
    assertThat(req.headers().get(BypassCheck.HEADER)).isEqualTo("config-uri-match");
    assertThat(req.headers().get(Deflector.TRAFFIC_TYPE_HEADER)).isEqualTo("unknown");
  }

  @Test
  public void testRemoteBypassSkipsProtection() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .bypass(new ProtectionBypassConfig(null, List.of("127.0.0.1")))
            .cookieGates(List.of(CookieGateConfig.builder().name(COOKIE_NAME).build()))
            .build();
    Supplier<ChannelHandler> supplier = Protection.handlerSupplier(protection, new ProxyMetrics());
    assertThat(supplier).isNotNull();
    initWithRemote(supplier.get(), "127.0.0.1", 1234);
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/private");
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isTrue();
    assertThat(messageWritten).isNull();
    assertThat(req.headers().get(BypassCheck.HEADER)).isEqualTo("config-ip-match");
    assertThat(req.headers().get(Deflector.TRAFFIC_TYPE_HEADER)).isEqualTo("unknown");
  }

  /// Every configured prefix counts, also when a longer prefix sorts in between.
  @Test
  public void overlappingPathPrefixes() throws Exception {
    ProtectionBypass bypass =
        new ProtectionBypass(new ProtectionBypassConfig(List.of("/a*", "/ab*", "/exact"), null));
    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ChannelHandlerContext ctx = ch.pipeline().firstContext();
    for (String uri : List.of("/a", "/ab", "/abc", "/ac", "/exact")) {
      assertThat(bypass.allowBypass(ctx, get(uri))).as("bypass " + uri).isTrue();
    }
    for (String uri : List.of("/", "/b", "/exact/more")) {
      assertThat(bypass.allowBypass(ctx, get(uri))).as("no bypass " + uri).isFalse();
    }
  }

  @Test
  public void suffixIsNotSupported() {
    assertThatThrownBy(
            () -> new ProtectionBypass(new ProtectionBypassConfig(List.of("*.png"), null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static HttpRequest get(String uri) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
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

  private void initWithRemote(ChannelHandler protectionHandler, String host, int port) {
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
    channel =
        new EmbeddedChannel(out, handler, in) {
          @Override
          public java.net.SocketAddress remoteAddress() {
            return new java.net.InetSocketAddress(host, port);
          }
        };
  }

  private void request(HttpRequest req) throws Exception {
    ChannelHandlerContext ctx = channel.pipeline().context(ProtectionHandler.class);
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
