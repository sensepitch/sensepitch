package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.function.Supplier;

/// @author Jens Wilke
public class SiteSelectorHandler extends SkippingChannelInboundHandlerAdapter {

  private final Supplier<SiteSelector> siteSelector;

  public SiteSelectorHandler(SiteSelector siteSelector) {
    this(() -> siteSelector);
  }

  /// @param siteSelector supplies the current selector, read once per request so a
  ///   configuration reload takes effect on keep-alive connections
  public SiteSelectorHandler(Supplier<SiteSelector> siteSelector) {
    this.siteSelector = siteSelector;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      var host = request.headers().get(HttpHeaderNames.HOST);
      var suppliers = siteSelector.get().getSuppliers(request, host);
      if (suppliers == null) {
        rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }
      RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
      if (requestLog != null) {
        requestLog.setRoute(suppliers.route());
      }
      ChannelHandler fallback = suppliers.fallbackSupplier().get();
      ChannelHandler protection = suppliers.protectionSupplier().get();
      ChannelHandler proxy = suppliers.proxySupplier().get();

      ctx.pipeline().replace("fallback", "fallback", fallback);
      ctx.pipeline().replace("protection", "protection", protection);
      ctx.pipeline().replace("proxy", "proxy", proxy);
    }
    super.channelRead(ctx, msg);
  }
}
