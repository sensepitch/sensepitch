package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Always respond with 404
 *
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class Dummy404Handler extends SkippingChannelInboundHandlerAdapter {

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
      return;
    }
    super.channelRead(ctx, msg);
  }

}
