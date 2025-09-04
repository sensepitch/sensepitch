package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * @author Jens Wilke
 */
public class IpTraitsHandler extends ChannelInboundHandlerAdapter {

  public static final String TRAITS_HEADER = "X-Sensepitch-Ip-Traits";
  public static final String COUNTRY_HEADER = "X-Sensepitch-Country";
  public static final String ASN_HEADER = "X-Sensepitch-Asn";

  private final IpTraitsLookup ipTraitsLookup;
  private String traits = null;
  private IpTraits ipTraits = null;

  public IpTraitsHandler(IpTraitsLookup ipTraitsLookup) {
    this.ipTraitsLookup = ipTraitsLookup;
  }

  public static String extract(HttpRequest request) {
    return request.headers().get(TRAITS_HEADER);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    // Debug.INSTANCE.trace(ctx.channel(), "IpTraitsHandler channelActive");
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetAddress address = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
      // Debug.INSTANCE.trace(ctx.channel(), "remoteAddress: " + address.getHostAddress());
      var builder = IpTraits.builder();
      ipTraitsLookup.lookup(builder, address);
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
      if (attributes.crawler()) {
        if (!collectTraits.isEmpty()) {
          collectTraits.append(", ");
        }
        collectTraits.append("crawler");
      }
      // TODO: add key/value traits
      traits = collectTraits.toString();
      // Debug.INSTANCE.trace(ctx.channel(), "trais: " + traits);
    }
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      if (traits != null) {
        request.headers().add(TRAITS_HEADER, traits);
        if (ipTraits.isoCountry() != null) {
          request.headers().add(COUNTRY_HEADER, ipTraits.isoCountry());
        }
        if (ipTraits.asn() >= 0) {
          request.headers().add(ASN_HEADER, ipTraits.asn());
        }
      }
    }
    super.channelRead(ctx, msg);
  }
}
