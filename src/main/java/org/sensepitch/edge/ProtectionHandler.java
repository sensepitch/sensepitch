package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

/**
 * Common handler logic for {@link ProtectionPlugin} implementations.
 *
 * @author Jens Wilke
 */
public class ProtectionHandler extends SkippingChannelInboundHandlerAdapter {

  private final ProtectionPlugin plugin;

  public ProtectionHandler(ProtectionPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      if (plugin.mightIntercept(request, ctx)) {
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else {
        ctx.fireChannelRead(msg);
      }
    } else {
      super.channelRead(ctx, msg);
    }
  }
}
