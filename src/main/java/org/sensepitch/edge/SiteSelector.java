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
import io.netty.util.concurrent.Future;
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
public class SiteSelector {

  private static final SiteConfig SITE_CONFIG_DEFAULT = SiteConfig.builder().build();
  private final Upstream defaultUpstream;
  private final Map<String, Suppliers> directHostMatch = new HashMap<>();
  private final Map<String, TreeMap<String, Suppliers>> sitePrefixUriMatch = new HashMap<>();

  public SiteSelector(ProxyContext ctx, ProxyConfig config) {
    if (config.sites() == null || config.sites().isEmpty()) {
      throw new IllegalArgumentException("sites missing");
    }
    if (config.upstream() != null) {
      defaultUpstream = constructUpstream(ctx, config.upstream());
    } else {
      defaultUpstream = null;
    }
    config
        .sites()
        .values()
        .forEach(
            site -> {
              Supplier<ChannelHandler> proxySupplier = constructProxySupplier(ctx, site);
              Supplier<ChannelHandler> protectionSupplier = null;
              ProtectionConfig protection = site.protection();
              if (protection == null) {
                protection = config.protection();
              }
              protectionSupplier = Protection.handlerSupplier(protection);
              if (protectionSupplier == null) {
                throw new IllegalArgumentException(
                    "Site requires protection scheme or explicit disable");
              }
              var suppliers = new Suppliers(protectionSupplier, proxySupplier);
              String host = site.host();
              if (host == null) {
                host = site.key();
              }
              if (host == null) {
                throw new IllegalArgumentException("Site requires host or key");
              }
              if (host.contains("*")) {
                // TODO: support wildcard hosts
                throw new IllegalArgumentException("Host wildcards are not yet supported");
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

  private Supplier<ChannelHandler> constructProxySupplier(ProxyContext ctx, SiteConfig site) {
    Upstream upstream;
    if (site.response() != null) {
      ResponseConfig response = site.response();
      HttpResponseStatus status;
      String location;
      if (response.permanentRedirect() != null) {
        location = response.permanentRedirect();
        status = HttpResponseStatus.PERMANENT_REDIRECT;
      } else if (response.temporaryRedirect() != null) {
        location = response.temporaryRedirect();
        status = HttpResponseStatus.TEMPORARY_REDIRECT;
      } else if (response.location() != null) {
        location = response.location();
        if (response.status() != 0) {
          status = HttpResponseStatus.valueOf(response.status());
        } else {
          status = HttpResponseStatus.FOUND;
        }
      } else {
        location = null;
        if (response.status() != 0) {
          status = HttpResponseStatus.valueOf(response.status());
        } else {
          status = HttpResponseStatus.OK;
        }
      }
      String text = response.text();
      if (text == null && location == null) {
        throw new IllegalArgumentException("Response requires redirect location or text");
      }
      upstream =
          new Upstream() {
            @Override
            public Future<Channel> connect(ChannelHandlerContext ingressContext) {
              Promise<Channel> promise = ingressContext.executor().newPromise();
              Channel ingressChannel = ingressContext.channel();
              Channel upstreamChannel =
                  EmbeddedChannel.builder()
                      .handlers(
                          new ChannelOutboundHandlerAdapter() {
                            @Override
                            public void write(
                                ChannelHandlerContext ctx1, Object msg, ChannelPromise promise) {
                              if (msg instanceof HttpRequest) {
                                ByteBuf content = Unpooled.EMPTY_BUFFER;
                                if (text != null) {
                                  content = Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
                                }
                                FullHttpResponse response =
                                    new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1, status, content);
                                response
                                    .headers()
                                    .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                                if (location != null) {
                                  response.headers().set(HttpHeaderNames.LOCATION, location);
                                }
                                ingressChannel.writeAndFlush(response);
                              }
                              ReferenceCountUtil.release(msg);
                            }
                          })
                      .build();
              promise.setSuccess(upstreamChannel);
              return promise;
            }

            @Override
            public void release(Channel ch) {
              // ignore, embedded
            }
          };
    } else if (site.upstream() != null) {
      upstream = constructUpstream(ctx, site.upstream());
    } else if (defaultUpstream != null) {
      upstream = defaultUpstream;
    } else {
      throw new IllegalArgumentException("upstream missing");
    }
    Supplier<ChannelHandler> proxySupplier = () -> new DownstreamHandler(upstream, ctx.metrics());
    return proxySupplier;
  }

  private static DefaultUpstream constructUpstream(ProxyContext ctx, UpstreamConfig cfg) {
    return new DefaultUpstream(ctx, cfg);
  }

  public Set<String> getServicedHosts() {
    Set<String> hosts = new HashSet<>();
    hosts.addAll(directHostMatch.keySet());
    hosts.addAll(sitePrefixUriMatch.keySet());
    return hosts;
  }

  public Suppliers getSuppliers(HttpRequest request, String host) {
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
    return suppliers;
  }

  record Suppliers(
      Supplier<ChannelHandler> protectionSupplier, Supplier<ChannelHandler> proxySupplier) {}
}
