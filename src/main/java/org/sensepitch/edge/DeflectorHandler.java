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
import org.sensepitch.edge.ProxyMetrics.DeflectorOutcome;

/// @author Jens Wilke
public class DeflectorHandler implements ProtectionPlugin {

  private static final ProxyLogger LOG = ProxyLogger.get(DeflectorHandler.class);

  private final Deflector deflector;
  private final ProxyMetrics metrics;

  DeflectorHandler(Deflector deflector, ProxyMetrics metrics) {
    this.deflector = deflector;
    this.metrics = metrics;
  }

  @Override
  public boolean mightIntercept(HttpRequest request, ChannelHandlerContext ctx) {
    if (deflector.checkAdmissionCookie(request)) {
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_USER);
      metrics.countDeflectorRequest(request, DeflectorOutcome.PASSED);
      report(ctx, DeflectorOutcome.PASSED);
      return false;
    } else if (deflector.needsBypass(ctx, request)) {
      metrics.countDeflectorRequest(request, DeflectorOutcome.BYPASSED);
      report(ctx, DeflectorOutcome.BYPASSED);
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
      // reported before responding, the request is logged when the response is written
      boolean correct = deflector.verifyChallengeAnswer(request);
      DeflectorOutcome outcome =
          correct ? DeflectorOutcome.ANSWER_ACCEPTED : DeflectorOutcome.ANSWER_REJECTED;
      metrics.countDeflectorRequest(request, outcome);
      report(ctx, outcome);
      deflector.respondToChallengeAnswer(ctx, correct);
    } else {
      // TODO: behaviour of non GET requests?
      request.headers().set(Deflector.TRAFFIC_FLAVOR_HEADER, Deflector.FLAVOR_DEFLECT);
      metrics.countDeflectorRequest(request, DeflectorOutcome.CHALLENGE_SENT);
      report(ctx, DeflectorOutcome.CHALLENGE_SENT);
      deflector.outputChallengeHtml(ctx);
    }
    return true;
  }

  private static void report(ChannelHandlerContext ctx, DeflectorOutcome outcome) {
    RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
    if (requestLog != null) {
      requestLog.setProtectionOutcome(outcome);
    }
  }
}
