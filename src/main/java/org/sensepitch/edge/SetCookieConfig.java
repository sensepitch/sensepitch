package org.sensepitch.edge;

import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import lombok.Builder;

/// A cookie set by a configured response, see [ResponseConfig#cookies()]. The cookie is always
/// `Secure` and `HttpOnly`.
///
/// @param name cookie name, mandatory
/// @param value cookie value, defaults to [#DEFAULT_VALUE]
/// @param maxAge lifetime in seconds, `0` for a session cookie
/// @param delete if `true`, the cookie is deleted in the browser via `Max-Age=0`. Not combinable
///   with `maxAge`.
/// @param path cookie path, defaults to `/`
/// @param domain cookie domain, if unset the cookie is host-only
/// @param sameSite `Strict`, `Lax` or `None`, defaults to `Lax`
/// @author Jens Wilke
@Builder(toBuilder = true)
public record SetCookieConfig(
    String name,
    String value,
    int maxAge,
    boolean delete,
    String path,
    String domain,
    String sameSite) {

  public static final String DEFAULT_VALUE = "1";

  public SetCookieConfig {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("cookie name missing");
    }
    if (delete && maxAge != 0) {
      throw new IllegalArgumentException("cookie delete and maxAge are exclusive: " + name);
    }
    if (maxAge < 0) {
      throw new IllegalArgumentException("cookie maxAge must not be negative: " + name);
    }
    if (value == null) {
      value = DEFAULT_VALUE;
    }
    if (path == null) {
      path = "/";
    }
    if (sameSite == null) {
      sameSite = CookieHeaderNames.SameSite.Lax.name();
    } else {
      CookieHeaderNames.SameSite parsed = parseSameSite(sameSite);
      if (parsed == null) {
        throw new IllegalArgumentException(
            "cookie sameSite must be Strict, Lax or None, was: " + sameSite);
      }
      sameSite = parsed.name();
    }
  }

  /// Case-insensitive lookup, `null` if unknown.
  private static CookieHeaderNames.SameSite parseSameSite(String s) {
    for (CookieHeaderNames.SameSite v : CookieHeaderNames.SameSite.values()) {
      if (v.name().equalsIgnoreCase(s)) {
        return v;
      }
    }
    return null;
  }

  /// The `Set-Cookie` header value.
  public String encode() {
    DefaultCookie cookie = new DefaultCookie(name, delete ? "" : value);
    cookie.setPath(path);
    cookie.setDomain(domain);
    cookie.setSecure(true);
    cookie.setHttpOnly(true);
    cookie.setSameSite(CookieHeaderNames.SameSite.valueOf(sameSite));
    if (delete) {
      cookie.setMaxAge(0);
    } else if (maxAge > 0) {
      cookie.setMaxAge(maxAge);
    }
    return ServerCookieEncoder.STRICT.encode(cookie);
  }
}
