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
import java.util.function.Supplier;

/// Redirect all incoming requests that are not in a domain list to a default target. For accepted
/// domains that start with www., a redirect will be made for request arriving without the www.
/// prefix.
///
/// @author Jens Wilke
public class UnservicedHost {

  private final Set<String> servicedDomains;
  private final String defaultLocation;
  private final Map<String, String> redirectToWWW = new HashMap<>();
  public static final String NOT_FOUND_URI = "/NOT_FOUND";

  /// @throws IllegalArgumentException if a serviced domain is not lower case, without port and
  ///   trailing dot, see [SanitizeHostHandler#requireNormalizedHost]
  public UnservicedHost(UnservicedHostConfig cfg) {
    cfg.servicedDomains().forEach(SanitizeHostHandler::requireNormalizedHost);
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
    return new Handler(() -> this);
  }

  /// Rejects or redirects requests for hosts that are not serviced.
  public static class Handler extends SkippingChannelInboundHandlerAdapter {

    private final Supplier<UnservicedHost> unservicedHost;

    /// @param unservicedHost supplies the current instance, read once per request so a
    ///   configuration reload takes effect on keep-alive connections
    public Handler(Supplier<UnservicedHost> unservicedHost) {
      this.unservicedHost = unservicedHost;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof HttpRequest request) {
        UnservicedHost current = unservicedHost.get();
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (SanitizeHostHandler.UNKNOWN_METHOD.equals(request.method())
            || host == null
            || SanitizeHostHandler.MISSING_HOST.equals(host)
            || SanitizeHostHandler.UNKNOWN_HOST.equals(host)) {
          rejectRequest(ctx, HttpResponseStatus.BAD_REQUEST);
          return;
        } else if (!current.servicedDomains.contains(host)) {
          String target = current.redirectToWWW.get(host);
          HttpResponseStatus status;
          if (target != null) {
            // general, permanent redirects better use 301 than 308
            status = HttpResponseStatus.MOVED_PERMANENTLY;
          } else {
            target = current.defaultLocation;
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
