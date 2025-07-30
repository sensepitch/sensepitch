package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class SiteSelectorHandler extends SkippingChannelInboundHandlerAdapter {

  private final Map<String, Suppliers> directHostMatch = new HashMap<>();
  private final Map<String, TreeMap<String, Suppliers>> sitePrefixUriMatch =  new HashMap<>();

  public SiteSelectorHandler(ProxyContext ctx, ProxyConfig config) {
    if (config.sites() == null) {
      throw new IllegalArgumentException("sites missing");
    }
    config.sites().forEach(site -> {
      Supplier<ChannelHandler> proxySupplier = constructProxySupplier(ctx, site);
      Supplier<ChannelHandler> protectionSupplier = null;
      ProtectionConfig protection = site.protection();
      if  (protection != null) {
        if (!protection.enabled()) {
          PassThroughHandler passThroughHandler = new PassThroughHandler();
          protectionSupplier = () -> passThroughHandler;
        } else if (protection.admission() != null) {
          AdmissionHandler sharedAdmission = new AdmissionHandler(protection.admission());
          protectionSupplier = () -> sharedAdmission;
        } else if (protection.cookieAdmission() != null) {
          CookieAdmissionConfig cookieAdmissionCfg = protection.cookieAdmission();
          protectionSupplier = () -> new CookieAdmissionHandler(cookieAdmissionCfg);
        } else if (config.admission() != null) {
          AdmissionHandler sharedAdmission = new AdmissionHandler(config.admission());
          protectionSupplier = () -> sharedAdmission;
        }
      }
      if (protectionSupplier == null) {
        throw new IllegalArgumentException("Site requires protection scheme");
      }
      var suppliers = new Suppliers(protectionSupplier, proxySupplier);
      String host = site.host();
      if (host == null) {
        host = site.key();
      }
      if (host == null) {
        throw new IllegalArgumentException("Site requires host or key");
      }
      if (site.uri() == null || site.uri().equals("*") || site.uri().equals("/*")) {
        directHostMatch.put(host, suppliers);
      } else if (site.uri().endsWith("*")) {
        String matchUri = site.uri().substring(0, site.uri().length() - 1);
        var tree = sitePrefixUriMatch.computeIfAbsent(host, k -> new TreeMap<>());
        tree.put(matchUri, suppliers);
      } else {
        throw new IllegalArgumentException("Site match uri needs to be prefix");
      }
    });
  }

  private static Supplier<ChannelHandler> constructProxySupplier(ProxyContext ctx, SiteConfig site) {
    Upstream upstream;
    if (site.upstream() == null) {
      if (site.responseText() == null) {
        throw new IllegalArgumentException("upstream missing");
      }
      int status = site.responseStatusCode();
      if (status == 0) {
        status = 200;
      }
      HttpResponseStatus responseStatus = HttpResponseStatus.valueOf(status);
      upstream = ingressContext -> {
        Promise<Channel> promise = ingressContext.executor().newPromise();
        Channel ingressChannel = ingressContext.channel();
        Channel upstreamChannel = EmbeddedChannel.builder()
          .handlers(
            new ChannelOutboundHandlerAdapter() {
              @Override
              public void write(ChannelHandlerContext ctx1, Object msg, ChannelPromise promise) {
                if (msg instanceof HttpRequest reqeust) {
                  ByteBuf content = Unpooled.EMPTY_BUFFER;
                  if (site.responseText() != null) {
                    content = Unpooled.copiedBuffer(site.responseText(), StandardCharsets.US_ASCII);
                  }
                  FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus, content);
                  response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                  ingressChannel.writeAndFlush(response);
                }
                ReferenceCountUtil.release(msg);
              }
            }
          )
          .build();
        promise.setSuccess(upstreamChannel);
        return promise;
      };
    } else {
      upstream = new DefaultUpstream(ctx, site.upstream());
    }
    Supplier<ChannelHandler> proxySupplier = () -> new DownstreamHandler(upstream, ctx.metrics());
    return proxySupplier;
  }


  public Set<String> getServicedHosts() {
    Set<String> hosts = new HashSet<>();
    hosts.addAll(directHostMatch.keySet());
    hosts.addAll(sitePrefixUriMatch.keySet());
    return hosts;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      var host = request.headers().get(HttpHeaderNames.HOST);
      var suppliers = directHostMatch.get(host);
      if (suppliers == null) {
        var tree = sitePrefixUriMatch.get(host);
        if (tree != null) {
          var entry = tree.floorEntry(request.uri());
          if (entry != null && request.uri().startsWith(entry.getKey())) {
            suppliers = entry.getValue();
          }
        }
      }
      if (suppliers == null) {
        rejectRequest(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }
      ctx.pipeline().replace("protection", "protection", suppliers.protectionSupplier().get());
      ctx.pipeline().replace("proxy", "proxy", suppliers.proxySupplier().get());
    }
    super.channelRead(ctx, msg);
  }

  record Suppliers(Supplier<ChannelHandler> protectionSupplier,
                   Supplier<ChannelHandler> proxySupplier) { }

}
