package org.sensepitch.edge;

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

import java.util.Set;

/**
 *
 *
 * @author Jens Wilke
 */
public class CookieAdmissionHandler extends SkippingChannelInboundHandlerAdapter {

  private CookieAdmissionConfig config;

  public CookieAdmissionHandler(CookieAdmissionConfig config) {
    this.config = config;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      if (config.uri().equals(request.uri())) {
        DefaultFullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
            HttpResponseStatus.FOUND);
        Cookie cookie = new DefaultCookie(config.cookieName(), "y");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 30);
        String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
        response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
        response.headers().set(HttpHeaderNames.LOCATION, config.redirectLocation());
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
      if (!isCookiePresent(request)) {
        DefaultFullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
          HttpResponseStatus.FORBIDDEN);
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
        if (c.name().equals(config.cookieName())) {
          return true;
        }
      }
    }
    return false;
  }

}
