package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author Jens Wilke
 */
public interface NoBypassCheck {

  NoBypassCheck FALSE =
      new NoBypassCheck() {
        @Override
        public boolean skipBypass(ChannelHandlerContext ctx, HttpRequest request) {
          return false;
        }
      };

  boolean skipBypass(ChannelHandlerContext ctx, HttpRequest request);
}
