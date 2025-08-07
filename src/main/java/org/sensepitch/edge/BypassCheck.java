package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author Jens Wilke
 */
public interface BypassCheck {

  BypassCheck DO_BYPASS = (ctx, request) -> true;
  BypassCheck NO_BYPASS = (ctx, request) -> false;

  /** Header is set when bypass is active with the reason */
  String HEADER = "X-Senseptich-Admission-Bypass";

  static void setBypassReason(HttpRequest request, String reason) {
    request.headers().add(HEADER, reason);
  }

  /**
   * Checks whether the admission challenge can be bypassed. This may set additional request headers
   * recording the matching bypass rule.
   */
  boolean allowBypass(Channel channel, HttpRequest request);
}
