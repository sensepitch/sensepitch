package org.sensepitch.edge;

/// Naming of the request headers the edge adds for the upstream, e.g. the traffic type, the JA4
/// fingerprint or the IP traits. All start with [#PREFIX], short like Cloudflare's `Cf-*`, and
/// without the `X-` that RFC 6648 deprecates.
///
/// A client must not set these headers itself, else it could pretend to be a crawler or human
/// traffic. [StripEdgeHeadersHandler] removes them from every incoming request.
///
/// @author Jens Wilke
public final class SensepitchHeaders {

  /// Prefix of all request headers the edge adds
  public static final String PREFIX = "Sp-";

  private SensepitchHeaders() {}

  /// @return `true` if the header name starts with [#PREFIX], ignoring case
  public static boolean isEdgeHeader(CharSequence name) {
    int length = PREFIX.length();
    if (name.length() < length) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (Character.toLowerCase(name.charAt(i)) != Character.toLowerCase(PREFIX.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
