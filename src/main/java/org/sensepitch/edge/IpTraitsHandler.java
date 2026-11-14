package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

/// @author Jens Wilke
public class IpTraitsHandler extends ChannelInboundHandlerAdapter {

  public static final String TRAITS_HEADER = SensepitchHeaders.PREFIX + "Ip-Traits";
  public static final String COUNTRY_HEADER = SensepitchHeaders.PREFIX + "Country";
  public static final String ASN_HEADER = SensepitchHeaders.PREFIX + "Asn";

  private final Supplier<IpTraitsLookup> ipTraitsLookup;
  private String traits = null;
  private IpTraits ipTraits = null;

  public IpTraitsHandler(IpTraitsLookup ipTraitsLookup) {
    this(() -> ipTraitsLookup);
  }

  /// @param ipTraitsLookup supplies the current lookup, read once when the connection becomes
  ///   active so a configuration reload takes effect for new connections
  public IpTraitsHandler(Supplier<IpTraitsLookup> ipTraitsLookup) {
    this.ipTraitsLookup = ipTraitsLookup;
  }

  public static String extract(HttpRequest request) {
    return request.headers().get(TRAITS_HEADER);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetAddress address = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
      var builder = IpTraits.builder();
      ipTraitsLookup.get().lookup(builder, address);
      var attributes = ipTraits = builder.build();
      StringBuilder collectTraits = new StringBuilder();
      if (attributes.isAsnKnown()) {
        if (!collectTraits.isEmpty()) {
          collectTraits.append(", ");
        }
        collectTraits.append("asn=");
        collectTraits.append(attributes.asn());
      }
      if (attributes.isoCountry() != null) {
        if (!collectTraits.isEmpty()) {
          collectTraits.append(", ");
        }
        collectTraits.append("country=");
        collectTraits.append(attributes.isoCountry());
      }
      if (attributes.isCrawler()) {
        if (!collectTraits.isEmpty()) {
          collectTraits.append(", ");
        }
        collectTraits.append("crawler=");
        collectTraits.append(attributes.crawlerName());
      }
      traits = collectTraits.toString();
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
