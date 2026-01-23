package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
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
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class CookieGateTest {

  private static final String COOKIE_NAME = "edge-access";
  private static final String ACCESS_URI = "/gate";

  private ProtectionHandler handler;
  private Channel channel;
  private boolean passed;
  private Object messageWritten;

  @Test
  public void testAccessUriSetsCookieForSecondLevelDomain() throws Exception {
    init(List.of(CookieGateConfig.builder().name(COOKIE_NAME).accessUri(ACCESS_URI).build()));
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ACCESS_URI);
    req.headers().set(HttpHeaderNames.HOST, "api.sub.example.com");
    request(req);
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            FullHttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(200);
              assertThat(response.headers().get(HttpHeaderNames.SET_COOKIE))
                  .isNotBlank();
              String payload =
                  ByteBufUtil.getBytes(response.content()).length == 0
                      ? ""
                      : response.content().toString(StandardCharsets.UTF_8);
              assertThat(payload).isEqualTo("welcome");
              Cookie cookie =
                  ClientCookieDecoder.STRICT.decode(
                      response.headers().get(HttpHeaderNames.SET_COOKIE));
              assertThat(cookie.name()).isEqualTo(COOKIE_NAME);
              assertThat(cookie.domain()).isEqualTo("example.com");
              assertThat(cookie.path()).isEqualTo("/");
              assertThat(cookie.isHttpOnly()).isTrue();
              assertThat(cookie.isSecure()).isTrue();
              assertThat(cookie.maxAge()).isEqualTo(60L * 60 * 24 * 30);
            });
  }

  @Test
  public void testMissingCookieRejectedWith404() throws Exception {
    init(List.of(CookieGateConfig.builder().name(COOKIE_NAME).build()));
    request(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/private"));
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(404);
              assertThat(response.headers().get(HttpHeaderNames.CONNECTION))
                  .isEqualTo(HttpHeaderValues.CLOSE.toString());
            });
  }

  @Test
  public void testAccessUriRedirectsWhenConfigured() throws Exception {
    init(
        List.of(
            CookieGateConfig.builder()
                .name(COOKIE_NAME)
                .accessUri(ACCESS_URI)
                .redirectUrl("https://example.com/login")
                .build()));
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
              assertThat(response.status().code()).isEqualTo(302);
              assertThat(response.headers().get(HttpHeaderNames.LOCATION))
                  .isEqualTo("https://example.com/login");
              Cookie cookie =
                  ClientCookieDecoder.STRICT.decode(
                      response.headers().get(HttpHeaderNames.SET_COOKIE));
              assertThat(cookie.name()).isEqualTo(COOKIE_NAME);
              assertThat(response.content().readableBytes()).isEqualTo(0);
            });
  }

  @Test
  public void testCookieAllowsRequestToPass() throws Exception {
    init(List.of(CookieGateConfig.builder().name(COOKIE_NAME).build()));
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/private");
    String cookieHeader =
        ServerCookieEncoder.STRICT.encode(new DefaultCookie(COOKIE_NAME, "1"));
    req.headers().set(HttpHeaderNames.COOKIE, cookieHeader);
    request(req);
    assertThat(passed).isTrue();
    assertThat(messageWritten).isNull();
  }

  @Test
  public void testExtractSecondLevelDomain() {
    assertThat(CookieGate.extractSecondLevelDomain("sub.example.com")).isEqualTo("example.com");
    assertThat(CookieGate.extractSecondLevelDomain("example.com")).isEqualTo("example.com");
    assertThat(CookieGate.extractSecondLevelDomain("localhost")).isEqualTo("localhost");
  }

  private void init(List<CookieGateConfig> configs) {
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
    CookieGate gate = new CookieGate(configs);
    handler = gate.newHandler();
    channel = new EmbeddedChannel(out, handler, in);
  }

  private void request(HttpRequest req) throws Exception {
    ChannelHandlerContext ctx = channel.pipeline().context(handler.getClass());
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
