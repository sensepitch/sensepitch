package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SniCompletionEvent;

/**
 * Capture the requested host via SNI and set the http host header accordingly. Warning: The SNI
 * host header is not limited to the configured domains, it can be anything sent by the client.
 *
 * @author Jens Wilke
 */
// FIXME: not used!!, host header is set always by the client
public class SniToHostHeader extends ChannelInboundHandlerAdapter {

  private String sniHost;

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof SniCompletionEvent) {
      SniCompletionEvent event = (SniCompletionEvent) evt;
      sniHost = event.hostname();
    }
    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      HttpRequest req = (HttpRequest) msg;
      if (sniHost != null) {
        req.headers().set(HttpHeaderNames.HOST, sniHost);
      }
    }
    super.channelRead(ctx, msg);
  }
}
