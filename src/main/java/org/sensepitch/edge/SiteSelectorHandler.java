package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * @author Jens Wilke
 */
public class SiteSelectorHandler extends SkippingChannelInboundHandlerAdapter {

  SiteSelector siteSelector;

  public SiteSelectorHandler(SiteSelector siteSelector) {
    this.siteSelector = siteSelector;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      var host = request.headers().get(HttpHeaderNames.HOST);
      var suppliers = siteSelector.getSuppliers(request, host);
      if (suppliers == null) {
        rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }
      ctx.pipeline().replace("protection", "protection", suppliers.protectionSupplier().get());
      ctx.pipeline().replace("proxy", "proxy", suppliers.proxySupplier().get());
    }
    super.channelRead(ctx, msg);
  }

}
