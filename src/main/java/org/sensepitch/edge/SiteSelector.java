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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/// @author Jens Wilke
public class SiteSelector {

  private static final SiteConfig SITE_CONFIG_DEFAULT = SiteConfig.builder().build();
  private final Map<String, Upstream> namedUpstreams = new HashMap<>();
  private final Upstream defaultUpstream;
  private final Map<String, Routing<Suppliers>> directHostMatch = new HashMap<>();
  private final Map<String, TreeMap<String, Routing<Suppliers>>> sitePrefixUriMatch =
      new HashMap<>();

  public SiteSelector(ProxyContext ctx, ProxyConfig config) {
    if (config.sites() == null || config.sites().isEmpty()) {
      throw new IllegalArgumentException("sites missing");
    }
    if (config.upstreams() != null) {
      config
          .upstreams()
          .forEach(
              (name, cfg) -> {
                if (cfg != null && cfg.ref() != null) {
                  throw new IllegalArgumentException(
                      "Named upstream must not reference another upstream: " + name);
                }
                namedUpstreams.put(name, resolveUpstream(ctx, cfg));
              });
    }
    if (config.upstream() != null) {
      defaultUpstream = resolveUpstream(ctx, config.upstream());
    } else {
      defaultUpstream = null;
    }
    config
        .sites()
        .values()
        .forEach(
            site -> {
              Supplier<ChannelHandler> fallbackSupplier =
                  constructFallbackSupplier(config.fallback(), site.fallback());
              ProtectionConfig protection = site.protection();
              if (protection == null) {
                protection = config.protection();
              }
              Supplier<ChannelHandler> protectionSupplier = Protection.handlerSupplier(protection);
              Routing<Suppliers> routing =
                  constructRouting(ctx, site, fallbackSupplier, protectionSupplier);
              if (protectionSupplier == null) {
                throw new IllegalArgumentException(
                    "Site requires protection scheme or explicit disable");
              }
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
                directHostMatch.put(host, routing);
              } else if (site.uri().endsWith("*")) {
                String matchUri = site.uri().substring(0, site.uri().length() - 1);
                var tree = sitePrefixUriMatch.computeIfAbsent(host, k -> new TreeMap<>());
                tree.put(matchUri, routing);
              } else {
                throw new IllegalArgumentException("Site match uri needs to be prefix");
              }
            });
  }

  private Supplier<ChannelHandler> constructFallbackSupplier(
      FallbackConfig global, FallbackConfig site) {
    Fallback fallback = new Fallback(FallbackConfig.DEFAULTS.merge(global).merge(site));

    return () -> fallback.newHandler();
  }

  /// Constructs the routing of a site. Its targets are complete [Suppliers], one per upstream, so
  /// [#getSuppliers] does all request based decisions.
  private Routing<Suppliers> constructRouting(
      ProxyContext ctx,
      SiteConfig site,
      Supplier<ChannelHandler> fallbackSupplier,
      Supplier<ChannelHandler> protectionSupplier) {
    Function<Upstream, Suppliers> leaf =
        upstream ->
            new Suppliers(
                fallbackSupplier,
                protectionSupplier,
                () -> new DownstreamHandler(upstream, ctx.metrics()));
    Upstream upstream;
    if (site.response() != null) {
      upstream = constructResponseUpstream(site.response());
    } else if (site.upstream() != null) {
      upstream = resolveUpstream(ctx, site.upstream());
    } else if (defaultUpstream != null) {
      upstream = defaultUpstream;
    } else {
      throw new IllegalArgumentException("upstream missing");
    }
    List<Routing.CookieOverride<Suppliers>> overrides = new ArrayList<>();
    if (site.overrides() != null) {
      for (RoutingOverrideConfig override : site.overrides()) {
        if (override.whenCookie() == null) {
          throw new IllegalArgumentException("Routing override requires whenCookie");
        }
        if (override.upstream() == null) {
          throw new IllegalArgumentException(
              "Routing override requires upstream: " + override.whenCookie());
        }
        Suppliers overrideDefault = leaf.apply(resolveUpstream(ctx, override.upstream()));
        overrides.add(
            new Routing.CookieOverride<>(
                override.whenCookie(),
                new Routing<>(
                    overrideDefault, constructPaths(ctx, override.paths(), leaf), List.of())));
      }
    }
    return new Routing<>(leaf.apply(upstream), constructPaths(ctx, site.paths(), leaf), overrides);
  }

  private PrefixTree<Suppliers> constructPaths(
      ProxyContext ctx, Map<String, PathRouteConfig> paths, Function<Upstream, Suppliers> leaf) {
    PrefixTree<Suppliers> tree = new PrefixTree<>();
    if (paths == null) {
      return tree;
    }
    paths.forEach(
        (path, route) -> {
          if (!path.endsWith("*")) {
            throw new IllegalArgumentException("Path needs to be a prefix ending with *: " + path);
          }
          if (route == null || (route.upstream() == null) == (route.response() == null)) {
            throw new IllegalArgumentException("Path requires upstream or response: " + path);
          }
          Upstream upstream =
              route.response() != null
                  ? constructResponseUpstream(route.response())
                  : resolveUpstream(ctx, route.upstream());
          tree.put(path.substring(0, path.length() - 1), leaf.apply(upstream));
        });
    return tree;
  }

  private static Upstream constructResponseUpstream(ResponseConfig cfg) {
    HttpResponseStatus status = HttpResponseStatus.valueOf(cfg.status());
    String location;
    if (cfg.location() != null) {
      location = cfg.location();
    } else {
      location = null;
      if (cfg.status() != 0) {
        status = HttpResponseStatus.valueOf(cfg.status());
      } else {
        status = HttpResponseStatus.OK;
      }
    }
    String text = cfg.text();
    if (text == null && location == null) {
      throw new IllegalArgumentException("Response requires redirect location or text");
    }
    // status is reassigned above, so capture an effectively final copy for the anonymous upstream
    HttpResponseStatus finalStatus = status;
    return new Upstream() {
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
                                  HttpVersion.HTTP_1_1, finalStatus, content);
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
  }

  /// Returns the named upstream for a reference, otherwise constructs a new upstream.
  private Upstream resolveUpstream(ProxyContext ctx, UpstreamConfig cfg) {
    if (cfg == null) {
      throw new IllegalArgumentException("upstream missing");
    }
    if (cfg.ref() != null) {
      if (cfg.target() != null || cfg.connectionPool() != null) {
        throw new IllegalArgumentException(
            "Upstream ref must not be combined with other settings: " + cfg.ref());
      }
      Upstream upstream = namedUpstreams.get(cfg.ref());
      if (upstream == null) {
        throw new IllegalArgumentException("unknown upstream ref: " + cfg.ref());
      }
      return upstream;
    }
    if (cfg.target() == null) {
      throw new IllegalArgumentException("Upstream requires target or ref");
    }
    return new DefaultUpstream(ctx, cfg);
  }

  public Set<String> getServicedHosts() {
    Set<String> hosts = new HashSet<>();
    hosts.addAll(directHostMatch.keySet());
    hosts.addAll(sitePrefixUriMatch.keySet());
    return hosts;
  }

  /// Selects site and upstream for the request.
  ///
  /// @return the handler suppliers, or `null` if no site matches
  public Suppliers getSuppliers(HttpRequest request, String host) {
    var routing = directHostMatch.get(host);
    if (routing == null) {
      var tree = sitePrefixUriMatch.get(host);
      if (tree != null) {
        var entry = tree.floorEntry(request.uri());
        if (entry != null && request.uri().startsWith(entry.getKey())) {
          routing = entry.getValue();
        }
      }
    }
    return routing != null ? routing.select(request) : null;
  }

  record Suppliers(
      Supplier<ChannelHandler> fallbackSupplier,
      Supplier<ChannelHandler> protectionSupplier,
      Supplier<ChannelHandler> proxySupplier) {}
}
