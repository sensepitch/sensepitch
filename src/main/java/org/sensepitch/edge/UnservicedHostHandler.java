package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
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
@ChannelHandler.Sharable
public class UnservicedHostHandler extends SkippingChannelInboundHandlerAdapter {

  private final Set<String> servicedDomains;
  private final String defaultLocation;
  private final Map<String, String> domainRedirects = new HashMap<>();
  public static final String NOT_FOUND_URI = "/NOT_FOUND";

  public UnservicedHostHandler(UnservicedHostConfig cfg) {
    this.servicedDomains = Set.copyOf(cfg.servicedDomains());
    this.defaultLocation = cfg.defaultLocation();
    String WWW_PREFIX = "www.";
    servicedDomains.stream()
        .filter(s -> s.startsWith(WWW_PREFIX))
        .map(s -> s.substring(WWW_PREFIX.length()))
        .filter(s -> !servicedDomains.contains(s))
        .forEach(s -> domainRedirects.put(s, "https://" + WWW_PREFIX + s));
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      String host = ((HttpRequest) msg).headers().get(HttpHeaderNames.HOST);
      if (host == null
          || SanitizeHostHandler.MISSING_HOST.equals(host)
          || SanitizeHostHandler.UNKNOWN_HOST.equals(host)) {
        rejectRequest(ctx, HttpResponseStatus.BAD_REQUEST);
        return;
      } else if (!servicedDomains.contains(host)) {
        HttpResponseStatus status = HttpResponseStatus.PERMANENT_REDIRECT;
        String target = domainRedirects.get(host);
        if (target == null) {
          target = defaultLocation;
          status = HttpResponseStatus.TEMPORARY_REDIRECT;
        }
        if (target == null) {
          rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
        } else {
          // undefined URL should not do a redirect and result in status 200
          if (!"/".equals(request.uri())) {
            target += NOT_FOUND_URI;
          }
          FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
          response.headers().set(HttpHeaderNames.LOCATION, target);
          response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
          ctx.writeAndFlush(response);
          skipFollowingContent(ctx);
        }
        return;
      }
    }
    super.channelRead(ctx, msg);
  }
}
