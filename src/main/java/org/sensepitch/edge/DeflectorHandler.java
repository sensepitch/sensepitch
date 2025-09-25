package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author Jens Wilke
 */
public class DeflectorHandler extends SkippingChannelInboundHandlerAdapter {

  private static final ProxyLogger LOG = ProxyLogger.get(DeflectorHandler.class);

  Deflector deflector;

  DeflectorHandler(Deflector deflector) {
    this.deflector = deflector;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      if (deflector.checkAdmissionCookie(request)) {
        request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_USER);
        ctx.fireChannelRead(msg);
      } else if (deflector.needsBypass(ctx, request)) {
        ctx.fireChannelRead(msg);
      } else if (request.uri().startsWith(Deflector.CHALLENGE_STEP_URL)) {
        request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
        FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        // Prevent caching
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        response.headers().set(HttpHeaderNames.EXPIRES, "0");
        ctx.writeAndFlush(response);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else if (request.method() == HttpMethod.GET && request.uri().startsWith(Deflector.CHALLENGE_RESOURCES_URL)) {
        request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
        deflector.outputChallengeResources(ctx, request);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else if (request.method() == HttpMethod.GET && request.uri().startsWith(Deflector.CHALLENGE_ANSWER_URL)) {
        request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_USER);
        deflector.handleChallengeAnswer(ctx, request);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else {
        // TODO: behaviour of non GET requests?
        request
            .headers()
            .set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
        deflector.outputChallengeHtml(ctx);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      }
    } else {
      // this skips content if completely handled here
      super.channelRead(ctx, msg);
    }
  }

}
