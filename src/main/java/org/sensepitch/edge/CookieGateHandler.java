package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class CookieGateHandler extends SkippingChannelInboundHandlerAdapter {

  private final Map<String, CookieGateConfig> uri2config = new HashMap<>();
  private final Map<String, CookieGateConfig> cookie2config = new HashMap<>();

  public CookieGateHandler(List<CookieGateConfig> configs) {
    for (CookieGateConfig cfg : configs) {
      if (cfg.accessUri() != null) {
        uri2config.put(cfg.accessUri(), cfg);
      }
      if (cfg.name() == null) {
        throw new IllegalArgumentException("Cookie name is required");
      }
      cookie2config.put(cfg.name(), cfg);
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      CookieGateConfig cfg = uri2config.get(request.uri());
      if (cfg != null) {
        ByteBuf buf = Unpooled.copiedBuffer("welcome", CharsetUtil.UTF_8);
        DefaultFullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
        Cookie cookie = new DefaultCookie(cfg.name(), "y");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 30);
        String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
        response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
      if (!isCookiePresent(request)) {
        DefaultFullHttpResponse response =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
    }
    super.channelRead(ctx, msg);
  }

  private boolean isCookiePresent(HttpRequest request) {
    String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
    if (cookieHeader != null) {
      Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieHeader);
      for (Cookie c : cookies) {
        if (cookie2config.containsKey(c.name())) {
          return true;
        }
      }
    }
    return false;
  }
}
