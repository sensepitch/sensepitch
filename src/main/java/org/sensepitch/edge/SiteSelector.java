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
import java.util.function.Function;
import java.util.function.Supplier;

/// @author Jens Wilke
public class SiteSelector {

  private static final SiteConfig SITE_CONFIG_DEFAULT = SiteConfig.builder().build();

  /// Replaces the fallback for configured responses, which are sent as configured, also with
  /// status 500 or 503.
  private static final ChannelHandler NO_FALLBACK = new NoFallback();

  private final UpstreamRegistry upstreams;
  private final Map<String, Upstream> namedUpstreams = new HashMap<>();
  private final Upstream defaultUpstream;
  /// Sites by host and uri prefix, a site serving the whole host has the empty prefix
  private final Map<String, PrefixTree<Routing<Suppliers>>> sitesByHost = new HashMap<>();

  /// @param upstreams creates or takes over the upstreams, see [UpstreamRegistry] for the naming
  public SiteSelector(ProxyContext ctx, ProxyConfig config, UpstreamRegistry upstreams) {
    this.upstreams = upstreams;
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
                namedUpstreams.put(name, resolveUpstream("upstreams." + name, cfg));
              });
    }
    if (config.upstream() != null) {
      defaultUpstream = resolveUpstream("upstream", config.upstream());
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
              Supplier<ChannelHandler> protectionSupplier =
                  Protection.handlerSupplier(protection, ctx.metrics());
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
              String uriPrefix = uriPrefix(site.uri());
              var tree = sitesByHost.computeIfAbsent(host, k -> new PrefixTree<>());
              if (tree.put(uriPrefix, routing) != null) {
                throw new IllegalArgumentException(
                    "Duplicate site for host "
                        + host
                        + " and uri "
                        + (uriPrefix.isEmpty() ? "*" : uriPrefix + "*")
                        + ", site: "
                        + site.key());
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
    String sitePrefix = "sites." + (site.key() != null ? site.key() : site.host());
    Function<Upstream, Suppliers> leaf =
        upstream ->
            new Suppliers(
                fallbackSupplier,
                protectionSupplier,
                () -> new DownstreamHandler(upstream, ctx.metrics()));
    Function<ResponseConfig, Suppliers> responseLeaf =
        response -> {
          Upstream upstream = constructResponseUpstream(response);
          return new Suppliers(
              () -> NO_FALLBACK,
              protectionSupplier,
              () -> new DownstreamHandler(upstream, ctx.metrics()));
        };
    Suppliers defaultTarget;
    if (site.response() != null) {
      defaultTarget = responseLeaf.apply(site.response());
    } else if (site.upstream() != null) {
      defaultTarget = leaf.apply(resolveUpstream(sitePrefix + ".upstream", site.upstream()));
    } else if (defaultUpstream != null) {
      defaultTarget = leaf.apply(defaultUpstream);
    } else {
      throw new IllegalArgumentException("upstream missing");
    }
    List<Routing.CookieOverride<Suppliers>> overrides = new ArrayList<>();
    if (site.overrides() != null) {
      for (int i = 0; i < site.overrides().size(); i++) {
        RoutingOverrideConfig override = site.overrides().get(i);
        String overridePrefix = sitePrefix + ".overrides[" + i + "]";
        if (override.whenCookie() == null) {
          throw new IllegalArgumentException("Routing override requires whenCookie");
        }
        if (override.upstream() == null) {
          throw new IllegalArgumentException(
              "Routing override requires upstream: " + override.whenCookie());
        }
        Suppliers overrideDefault =
            leaf.apply(resolveUpstream(overridePrefix + ".upstream", override.upstream()));
        overrides.add(
            new Routing.CookieOverride<>(
                override.whenCookie(),
                constructRouting(
                    overridePrefix,
                    overrideDefault,
                    override.paths(),
                    leaf,
                    responseLeaf,
                    List.of())));
      }
    }
    return constructRouting(sitePrefix, defaultTarget, site.paths(), leaf, responseLeaf, overrides);
  }

  /// @param prefix name prefix of the upstreams, see [UpstreamRegistry]
  private Routing<Suppliers> constructRouting(
      String prefix,
      Suppliers defaultTarget,
      Map<String, PathRouteConfig> paths,
      Function<Upstream, Suppliers> leaf,
      Function<ResponseConfig, Suppliers> responseLeaf,
      List<Routing.CookieOverride<Suppliers>> overrides) {
    Map<String, Suppliers> exact = new HashMap<>();
    PrefixTree<Suppliers> tree = new PrefixTree<>();
    if (paths != null) {
      paths.forEach(
          (path, route) -> {
            if (route == null || (route.upstream() == null) == (route.response() == null)) {
              throw new IllegalArgumentException("Path requires upstream or response: " + path);
            }
            Suppliers target =
                route.response() != null
                    ? responseLeaf.apply(route.response())
                    : leaf.apply(
                        resolveUpstream(prefix + ".paths." + path + ".upstream", route.upstream()));
            if (path.endsWith("*")) {
              tree.put(path.substring(0, path.length() - 1), target);
            } else {
              exact.put(path, target);
            }
          });
    }
    return new Routing<>(defaultTarget, exact, tree, overrides);
  }

  private static Upstream constructResponseUpstream(ResponseConfig cfg) {
    String location = cfg.location();
    // ResponseConfig already defaults the status of a redirect
    HttpResponseStatus status =
        cfg.status() != 0 ? HttpResponseStatus.valueOf(cfg.status()) : HttpResponseStatus.OK;
    // read once, a missing file fails the startup
    byte[] body;
    if (location != null) {
      body = null;
    } else if (cfg.file() != null) {
      body = ResponseFiles.read(cfg.file());
    } else {
      body = cfg.text().getBytes(StandardCharsets.UTF_8);
    }
    String contentType =
        cfg.contentType() != null ? cfg.contentType() : FallbackConfig.DEFAULT_CONTENT_TYPE;
    List<String> cookies = cfg.encodedCookies();
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
                          ByteBuf content =
                              body != null ? Unpooled.wrappedBuffer(body) : Unpooled.EMPTY_BUFFER;
                          FullHttpResponse response =
                              new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
                          response
                              .headers()
                              .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
                          if (body != null) {
                            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                          }
                          if (location != null) {
                            response.headers().set(HttpHeaderNames.LOCATION, location);
                          }
                          for (String cookie : cookies) {
                            response.headers().add(HttpHeaderNames.SET_COOKIE, cookie);
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
    };
  }

  /// Returns the named upstream for a reference, otherwise the upstream from the registry.
  ///
  /// @param name name of the upstream, see [UpstreamRegistry], not used for a reference
  private Upstream resolveUpstream(String name, UpstreamConfig cfg) {
    if (cfg == null) {
      throw new IllegalArgumentException("upstream missing");
    }
    if (cfg.ref() != null) {
      if (cfg.target() != null) {
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
    return upstreams.get(name, cfg);
  }

  /// The prefix of a site uri without the trailing `*`. The empty prefix matches the whole host.
  /// `/*` is treated like `*`, so it also matches a request target that does not start with `/`,
  /// e.g. `OPTIONS *`.
  private static String uriPrefix(String uri) {
    if (uri == null || uri.equals("*") || uri.equals("/*")) {
      return "";
    }
    if (!uri.endsWith("*")) {
      throw new IllegalArgumentException("Site match uri needs to be prefix");
    }
    return uri.substring(0, uri.length() - 1);
  }

  public Set<String> getServicedHosts() {
    return new HashSet<>(sitesByHost.keySet());
  }

  /// Selects site and upstream for the request.
  ///
  /// @return the handler suppliers, or `null` if no site matches
  public Suppliers getSuppliers(HttpRequest request, String host) {
    var tree = sitesByHost.get(host);
    var routing = tree != null ? tree.lookup(request.uri()) : null;
    return routing != null ? routing.select(request) : null;
  }

  @ChannelHandler.Sharable
  private static final class NoFallback extends ChannelOutboundHandlerAdapter {}

  record Suppliers(
      Supplier<ChannelHandler> fallbackSupplier,
      Supplier<ChannelHandler> protectionSupplier,
      Supplier<ChannelHandler> proxySupplier) {}
}
