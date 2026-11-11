package org.sensepitch.edge;

import java.util.Locale;

/// Why a request ended, logged as `sensepitch.request.outcome`. The values are few, so they are
/// suited for grouping in dashboards: is it the edge, the upstream or the client?
///
/// [#COMPLETED] and [#EDGE_RESPONSE] are derived when nothing else was reported. The others are
/// reported by the handler that ended the request, the first report wins.
///
/// @author Jens Wilke
public enum RequestOutcome {

  /// The upstream or a static response route answered, the response was sent
  COMPLETED,

  /// No upstream was selected, the edge answered itself, e.g. with a challenge, a redirect of an
  /// unserviced host or because no site matched
  EDGE_RESPONSE,

  /// The error response of the upstream was replaced by the fallback page, see
  /// [RequestLogInfo#upstreamStatus()]
  FALLBACK,

  /// The connection to the upstream failed
  UPSTREAM_CONNECT_FAILED,

  /// Forwarding the request to the upstream failed
  UPSTREAM_WRITE_FAILED,

  /// The upstream did not respond within the response timeout
  UPSTREAM_TIMEOUT,

  /// The client did not complete the request within the read timeout
  REQUEST_TIMEOUT,

  /// The client did not take the response within the write timeout
  RESPONSE_WRITE_TIMEOUT,

  /// The connection was closed before the response was complete
  CLIENT_ABORTED,

  /// Another error, answered with a server error by the edge
  EDGE_ERROR;

  private final String value = name().toLowerCase(Locale.ROOT);

  /// Logged value, the lower case name
  public String value() {
    return value;
  }
}
