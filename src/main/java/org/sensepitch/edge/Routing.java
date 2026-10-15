package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Selects the routing target of a site by cookie triggered overrides, an exact path match or the
/// longest matching path prefix, see [SiteConfig#paths()] and [SiteConfig#overrides()].
///
/// @param <T> the routing target
/// @author Jens Wilke
public class Routing<T> {

  private final T defaultTarget;
  private final Map<String, T> exactPaths;
  private final PrefixTree<T> paths;
  private final List<CookieOverride<T>> overrides;

  /// @param defaultTarget target if no path matches
  /// @param exactPaths targets by exact path, matched against the request path without query,
  ///   precedes the prefixes
  /// @param paths targets by path prefix, the prefixes without the trailing `*`
  /// @param overrides routings replacing this one if the cookie is present, first match wins
  public Routing(
      T defaultTarget,
      Map<String, T> exactPaths,
      PrefixTree<T> paths,
      List<CookieOverride<T>> overrides) {
    this.defaultTarget = defaultTarget;
    this.exactPaths = Map.copyOf(exactPaths);
    this.paths = paths;
    this.overrides = List.copyOf(overrides);
  }

  public T select(HttpRequest request) {
    if (!overrides.isEmpty()) {
      String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
      if (cookieHeader != null) {
        Set<String> cookieNames = new HashSet<>();
        for (Cookie c : ServerCookieDecoder.LAX.decode(cookieHeader)) {
          cookieNames.add(c.name());
        }
        for (CookieOverride<T> override : overrides) {
          if (cookieNames.contains(override.cookieName())) {
            return override.routing().select(request);
          }
        }
      }
    }
    String uri = request.uri();
    if (!exactPaths.isEmpty()) {
      int query = uri.indexOf('?');
      T target = exactPaths.get(query >= 0 ? uri.substring(0, query) : uri);
      if (target != null) {
        return target;
      }
    }
    T target = paths.lookup(uri);
    return target != null ? target : defaultTarget;
  }

  /// Routing that applies when the cookie is present.
  public record CookieOverride<T>(String cookieName, Routing<T> routing) {}
}
