package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/// Augments the host header and sets only well-known values.
///
/// All valid clients are expected to set a correct host header according to the host they want to
/// contact. However, clients might leave the header out or just put garbage in there. We don't want
/// to have that further polluting our systems, or do defensive coding every time the host header is
/// needed.
///
/// @see RequestLogInfo#requestHeaderHost()
/// @author Jens Wilke
@ChannelHandler.Sharable
public class SanitizeHostHandler extends ChannelInboundHandlerAdapter {

  /// Alternative host that is set when the host from the client does not match any of the serviced
  /// hosts. The underscore is not a legal character within a hostname. This never collides with a
  /// real existing hostname.
  public static final String UNKNOWN_HOST = "unknown_host";

  /// Alternative host that is set in case the host header was absent.
  public static final String MISSING_HOST = "missing_host";

  /// Alternative host that is set if we never received a header. This value is used within the
  /// [RequestLoggingHandler] but defined here for completeness.
  public static final String NIL_HOST = "nil_host";

  /// Replacement for any method that we don't know or support.
  public static final HttpMethod UNKNOWN_METHOD = new HttpMethod("INVALID");

  // public static final HttpMethod UNSUPPORTED_METHOD = new HttpMethod("C/T");

  public static final Set<String> SPECIAL_HOSTS = Set.of(UNKNOWN_HOST, MISSING_HOST, NIL_HOST);

  public static final Set<String> SUPPORTED_HTTP_METHODS =
      Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "PATCH");

  public static final Set<String> UNSUPPORTED_HTTP_METHODS = Set.of("CONNECT", "TRACE");

  private final Supplier<Set<String>> knownHosts;

  public SanitizeHostHandler(Collection<String> knownHosts) {
    Set<String> hosts = filterKnownHosts(knownHosts);
    this.knownHosts = () -> hosts;
  }

  /// @param knownHosts supplies the current known hosts, already passed through
  ///   [#filterKnownHosts], read once per request so a configuration reload takes effect
  public SanitizeHostHandler(Supplier<Set<String>> knownHosts) {
    this.knownHosts = knownHosts;
  }

  /// Removes the special hosts, so a client can never send one of them as a valid host.
  ///
  /// @throws IllegalArgumentException if a host is not in the form of [#normalizeHost], e.g.
  ///   contains upper case letters, since it would never match a request
  public static Set<String> filterKnownHosts(Collection<String> knownHosts) {
    knownHosts.forEach(SanitizeHostHandler::requireNormalizedHost);
    return knownHosts.stream()
        .filter(s -> !SPECIAL_HOSTS.contains(s))
        .collect(Collectors.toUnmodifiableSet());
  }

  /// Checks a configured host, it needs to be in the form of [#normalizeHost] to match a request.
  ///
  /// @throws IllegalArgumentException if the host is not normalized, e.g. contains upper case
  ///   letters
  public static void requireNormalizedHost(String host) {
    if (!host.equals(normalizeHost(host))) {
      throw new IllegalArgumentException(
          "Host needs to be lower case, without port and trailing dot: " + host);
    }
  }

  /// Normalizes the value of a host header: removes the port, a trailing dot and surrounding
  /// whitespace and converts to lower case, since host names are case-insensitive. An IPv6
  /// address stays in brackets, e.g. `[2001:db8::1]`.
  ///
  /// @return the normalized host or `null` if the value is not a valid host with optional port
  static String normalizeHost(String value) {
    String host = value.strip();
    int portStart;
    if (host.startsWith("[")) {
      portStart = host.indexOf(']') + 1;
      if (portStart == 0) {
        return null;
      }
    } else {
      portStart = host.indexOf(':');
      if (portStart < 0) {
        portStart = host.length();
      }
    }
    if (!isPort(host, portStart)) {
      return null;
    }
    host = host.substring(0, portStart);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isEmpty() || host.endsWith(".")) {
      return null;
    }
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c <= ' ' || c >= 0x7f) {
        return null;
      }
    }
    return host.toLowerCase(Locale.ROOT);
  }

  /// True if `host` ends with an optional port starting at `start`: nothing, or a colon followed by
  /// up to 5 digits, the digits can be absent according to RFC 3986.
  private static boolean isPort(String host, int start) {
    if (start == host.length()) {
      return true;
    }
    if (host.charAt(start) != ':' || host.length() - start > 6) {
      return false;
    }
    for (int i = start + 1; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
      String host;
      if (hosts.isEmpty()) {
        host = MISSING_HOST;
      } else if (hosts.size() > 1) {
        // RFC 9112, 3.2: more than one host header must be rejected, see UnservicedHost
        host = UNKNOWN_HOST;
      } else {
        host = normalizeHost(hosts.get(0));
        if (host == null || !knownHosts.get().contains(host)) {
          host = UNKNOWN_HOST;
        }
      }
      // always set, so no other host header than the checked one reaches the upstream
      request.headers().set(HttpHeaderNames.HOST, host);
      // if we get strange methods
      if (!SUPPORTED_HTTP_METHODS.contains(request.method().name())) {
        request.setMethod(UNKNOWN_METHOD);
      }
    }
    super.channelRead(ctx, msg);
  }
}
