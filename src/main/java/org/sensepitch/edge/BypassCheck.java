package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;

/// @author Jens Wilke
public interface BypassCheck {

  BypassCheck DO_BYPASS = (ctx, request) -> true;
  BypassCheck NO_BYPASS = (ctx, request) -> false;

  /// Header is set when bypass is active with the reason
  String HEADER = "X-Sensepitch-Admission-Bypass";

  /// Bypass reason: the client address is in the configured `remotes`
  String REASON_CONFIG_IP_MATCH = "config-ip-match";

  /// Bypass reason: the request URI matches the configured `uris`
  String REASON_CONFIG_URI_MATCH = "config-uri-match";

  /// Bypass reason: the user agent is a known crawler
  String REASON_CRAWLER_AGENT_MATCH = "crawler-agent-match";

  /// Bypass reason: the user agent contains a known crawler fragment
  String REASON_CRAWLER_FRAGMENT_AGENT_MATCH = "crawler-fragment-agent-match";

  /// Bypass reason: the IP traits mark the client address as crawler
  String REASON_CRAWLER_IP_MATCH = "crawler-ip-match";

  /// Bypasses because of a configured remote address, see [#REASON_CONFIG_IP_MATCH]
  BypassCheck CONFIG_IP_MATCH_BYPASS =
      (ctx, request) -> {
        setBypassReason(request, REASON_CONFIG_IP_MATCH);
        return true;
      };

  /// Bypasses because of a configured URI, see [#REASON_CONFIG_URI_MATCH]
  BypassCheck CONFIG_URI_MATCH_BYPASS =
      (ctx, request) -> {
        setBypassReason(request, REASON_CONFIG_URI_MATCH);
        return true;
      };

  static void setBypassReason(HttpRequest request, String reason) {
    request.headers().add(HEADER, reason);
  }

  /// Checks whether the admission challenge can be bypassed. This may set additional request
  /// headers recording the matching bypass rule.
  boolean allowBypass(Channel channel, HttpRequest request);
}
