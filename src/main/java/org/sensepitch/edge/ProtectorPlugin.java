package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author Jens Wilke
 */
public interface ProtectorPlugin {

  /**
   * Inspect http request and either handle it by blocking or allow passage
   *
   * @returns true, if request was intercepted and processed, the following data can be skipped, or,
   *          false, if request passed protection checks and can be passed on
   */
  boolean mightIntercept(HttpRequest request, ChannelHandlerContext ctx);

}
