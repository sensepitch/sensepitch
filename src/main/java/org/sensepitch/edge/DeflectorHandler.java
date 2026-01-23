package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * @author Jens Wilke
 */
public class DeflectorHandler implements ProtectionPlugin {

  private static final ProxyLogger LOG = ProxyLogger.get(DeflectorHandler.class);

  Deflector deflector;

  DeflectorHandler(Deflector deflector) {
    this.deflector = deflector;
  }

  @Override
  public boolean mightIntercept(HttpRequest request, ChannelHandlerContext ctx) {
    if (deflector.checkAdmissionCookie(request)) {
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_USER);
      return false;
    } else if (deflector.needsBypass(ctx, request)) {
      return false;
    } else if (request.uri().startsWith(Deflector.CHALLENGE_STEP_URL)) {
      // this is just for progress reporting and debugging
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
      FullHttpResponse response =
        new DefaultFullHttpResponse(
          HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
      // Prevent caching
      response
        .headers()
        .set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
      response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
      response.headers().set(HttpHeaderNames.EXPIRES, "0");
      ctx.writeAndFlush(response);
    } else if (request.method() == HttpMethod.GET
      && request.uri().startsWith(Deflector.CHALLENGE_RESOURCES_URL)) {
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
      deflector.outputChallengeResources(ctx, request);
    } else if (request.method() == HttpMethod.GET
      && request.uri().startsWith(Deflector.CHALLENGE_ANSWER_URL)) {
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_USER);
      deflector.handleChallengeAnswer(ctx, request);
    } else {
      // TODO: behaviour of non GET requests?
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
      deflector.outputChallengeHtml(ctx);
    }
    return true;
  }
}
