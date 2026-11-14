package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/// @author Jens Wilke
public class IpTraitsHandler extends ChannelInboundHandlerAdapter {

  public static final String TRAITS_HEADER = SensepitchHeaders.PREFIX + "Ip-Traits";
  public static final String COUNTRY_HEADER = SensepitchHeaders.PREFIX + "Country";
  public static final String ASN_HEADER = SensepitchHeaders.PREFIX + "Asn";

  private final IpTraitsLookup ipTraitsLookup;
  private String traits = null;
  private IpTraits ipTraits = null;

  /// @param ipTraitsLookup the lookup captured when this connection's pipeline is created
  public IpTraitsHandler(IpTraitsLookup ipTraitsLookup) {
    this.ipTraitsLookup = ipTraitsLookup;
  }

  public static String extract(HttpRequest request) {
    return request.headers().get(TRAITS_HEADER);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetAddress address = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
      ipTraits = ipTraitsLookup.lookup(address);
      traits = ipTraits.header();
    }
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      // never let a client pretend to have traits, e.g. to pass as a crawler, see DetectCrawler
      request.headers().remove(TRAITS_HEADER);
      request.headers().remove(COUNTRY_HEADER);
      request.headers().remove(ASN_HEADER);
      if (traits != null) {
        request.headers().set(TRAITS_HEADER, traits);
        if (ipTraits.isoCountry() != null) {
          request.headers().set(COUNTRY_HEADER, ipTraits.isoCountry());
        }
        if (ipTraits.asn() >= 0) {
          request.headers().set(ASN_HEADER, ipTraits.asn());
        }
      }
    }
    super.channelRead(ctx, msg);
  }
}
