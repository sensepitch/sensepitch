package org.sensepitch.edge;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * @author Jens Wilke
 */
public class ReportIoErrorsHandler extends ChannelDuplexHandler {

  static ProxyLogger DEBUG = ProxyLogger.get(ReportIoErrorsHandler.class);

  private String designation = "none";

  public ReportIoErrorsHandler(String designation) {
    this.designation = designation;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    DEBUG.error(ctx.channel(), designation + "@read", cause);
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    promise.addListener(
        (ChannelFuture future) -> {
          if (!future.isSuccess()) {
            DEBUG.error(future.channel(), designation + "@write", future.cause());
          }
        });
    super.write(ctx, msg, promise);
  }
}
