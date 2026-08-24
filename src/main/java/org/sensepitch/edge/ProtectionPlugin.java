package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/// @author Jens Wilke
public interface ProtectionPlugin {

  /// Inspect http request and either handle it by blocking or allow passage
  ///
  /// @return `true` if the request was intercepted and processed, so the following data can be
  ///   skipped; `false` if the request passed protection checks and can be passed on
  boolean mightIntercept(HttpRequest request, ChannelHandlerContext ctx);
}
