package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;

/// Sends the JA4 fingerprint of the connection to the upstream, in the same way {@link
/// IpTraitsHandler} sends the traits of the client address.
///
/// @see Ja4Handler
@ChannelHandler.Sharable
public class Ja4HeaderHandler extends ChannelInboundHandlerAdapter {

  public static final String JA4_HEADER = "X-Sensepitch-Ja4";
  public static final String JA4_RAW_HEADER = "X-Sensepitch-Ja4-Raw";

  private final boolean sendRaw;

  public Ja4HeaderHandler(Ja4Config config) {
    this.sendRaw = config.raw();
  }

  public static String extract(HttpRequest request) {
    return request.headers().get(JA4_HEADER);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      // never let a client pretend to have a fingerprint
      request.headers().remove(JA4_HEADER);
      request.headers().remove(JA4_RAW_HEADER);
      Ja4Fingerprint fingerprint = Ja4Handler.lookup(ctx.channel());
      if (fingerprint != null) {
        request.headers().set(JA4_HEADER, fingerprint.value());
        if (sendRaw) {
          request.headers().set(JA4_RAW_HEADER, fingerprint.raw());
        }
      }
    }
    super.channelRead(ctx, msg);
  }
}
