package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Redirect all incoming requests that are not in a domain list to a default target. For accepted
 * domains that start with www., a redirect will be made for request arriving without the www.
 * prefix.
 *
 * @author Jens Wilke
 */
public class UnservicedHost {

  private final Set<String> servicedDomains;
  private final String defaultLocation;
  private final Map<String, String> redirectToWWW = new HashMap<>();
  public static final String NOT_FOUND_URI = "/NOT_FOUND";

  public UnservicedHost(UnservicedHostConfig cfg) {
    this.servicedDomains = Set.copyOf(cfg.servicedDomains());
    this.defaultLocation = cfg.defaultLocation();
    String WWW_PREFIX = "www.";
    servicedDomains.stream()
        .filter(s -> s.startsWith(WWW_PREFIX))
        .map(s -> s.substring(WWW_PREFIX.length()))
        .filter(s -> !servicedDomains.contains(s))
        .forEach(s -> redirectToWWW.put(s, "https://" + WWW_PREFIX + s));
  }

  public Handler newHandler() {
    return new Handler();
  }

  public class Handler extends SkippingChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof HttpRequest request) {
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (SanitizeHostHandler.UNKNOWN_METHOD.equals(request.method())
            || host == null
            || SanitizeHostHandler.MISSING_HOST.equals(host)
            || SanitizeHostHandler.UNKNOWN_HOST.equals(host)) {
          rejectRequest(ctx, HttpResponseStatus.BAD_REQUEST);
          return;
        } else if (!servicedDomains.contains(host)) {
          String target = redirectToWWW.get(host);
          HttpResponseStatus status;
          if (target != null) {
            // general, permanent redirects better use 301 than 308
            status = HttpResponseStatus.MOVED_PERMANENTLY;
          } else {
            target = defaultLocation;
            status = HttpResponseStatus.TEMPORARY_REDIRECT;
          }
          if (target != null) {
            // undefined URL should not do a redirect and result in status 200
            if (!"/".equals(request.uri())) {
              target += NOT_FOUND_URI;
            }
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.headers().set(HttpHeaderNames.LOCATION, target);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response);
            skipFollowingContent(ctx);
            return;
          }
          rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
          return;
        }
      }
      super.channelRead(ctx, msg);
    }
  }
}
