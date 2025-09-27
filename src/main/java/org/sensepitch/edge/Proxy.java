package org.sensepitch.edge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.sensepitch.edge.config.KeyInjector;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.representer.Representer;

/** Minimal HTTP/1.1 proxy without aggregation, with keep-alive and basic logging */
public class Proxy implements ProxyContext {

  ProxyLogger LOG = ProxyLogger.get(Proxy.class);

  private final Dummy404Handler dummy404Handler = new Dummy404Handler();
  private final TrackIngressConnectionsHandler trackIngressConnectionsHandler;
  private final ProxyMetrics metrics = new ProxyMetrics();
  private final ProxyConfig config;
  private final ConnectionConfig connectionConfig;
  private final MetricsBridge metricsBridge;
  // private final SslContext sslContext;
  private final Mapping<String, SslContext> sniMapping;
  private final UnservicedHostHandler unservicedHostHandler;
  // private final DownstreamHandler downstreamHandler;
  // private final UpstreamRouter upstreamRouter;
  private final IpTraitsLookup ipTraitsLookup;
  private final EventLoopGroup eventLoopGroup;
  private final RequestLogger requestLogger;
  private final SanitizeHostHandler sanitizeHostHandler;
  private final SiteSelector siteSelector;

  public Proxy(ProxyConfig config) {
    config = KeyInjector.injectAllMapKeys(config);
    if (config.listen() == null) {
      throw new IllegalArgumentException("Missing listen configuration");
    }
    if (config.listen().connection() == null) {
      connectionConfig = ConnectionConfig.DEFAULT;
    } else {
      connectionConfig = config.listen().connection();
    }
    eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    this.config = config;
    metricsBridge = initializeMetrics();
    metricsBridge.expose(metrics);
    trackIngressConnectionsHandler = metricsBridge.expose(new TrackIngressConnectionsHandler());
    // sslContext = initializeSslContext();
    siteSelector = new SiteSelector(this, config);
    var servicedHosts = siteSelector.getServicedHosts();
    sniMapping = initializeSniMapping(config.listen(), servicedHosts);
    UnservicedHostConfig unservicedHostConfig =
        config.unservicedHost() != null
            ? config.unservicedHost()
            : UnservicedHostConfig.builder().build();
    if (unservicedHostConfig.servicedDomains() == null) {
      unservicedHostConfig =
          unservicedHostConfig.toBuilder().servicedDomains(servicedHosts).build();
    }
    unservicedHostHandler = new UnservicedHostHandler(unservicedHostConfig);
    Set<String> knownHosts = new HashSet<>();
    knownHosts.addAll(servicedHosts);
    if (config.listen().hosts() != null) {
      knownHosts.addAll(config.listen().hosts());
    }
    sanitizeHostHandler = new SanitizeHostHandler(knownHosts);
    requestLogger =
        new DistributingRequestLogger(
            new StandardOutRequestLogger(),
            metricsBridge.expose(new ExposeRequestCountPerStatusCodeHandler()));
    try {
      if (config.ipLookup() != null) {
        ipTraitsLookup = new CombinedIpTraitsLookup(config.ipLookup());
      } else {
        ipTraitsLookup = (builder, address) -> {};
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void dumpConfig(ProxyConfig proxyConfig) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setPrettyFlow(true);
    Representer repr = new Representer(options);
    repr.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
    Yaml yaml = new Yaml(repr, options);
    System.out.println(yaml.dump(proxyConfig));
  }

  MetricsBridge initializeMetrics() {
    MetricsConfig metricsConfig = config.metrics();
    if (metricsConfig == null) {
      metricsConfig = MetricsConfig.DEFAULT;
    }
    PrometheusConfig prometheusConfig = metricsConfig.prometheus();
    if (prometheusConfig == null) {
      prometheusConfig = PrometheusConfig.DEFAULT;
    }
    if (!metricsConfig.enable()) {
      return new MetricsBridge.NoMetricsExposed();
    }
    return new PrometheusMetricsBridge(prometheusConfig);
  }

  static Mapping<String, SslContext> initializeSniMapping(
      ListenConfig cfg, Set<String> servicedHosts) {
    var knownHosts = new HashSet<>(servicedHosts);
    if (cfg.hosts() != null) {
      knownHosts.addAll(cfg.hosts());
    }
    var contexts = new LinkedHashMap<String, SslContext>();
    if (cfg.letsEncrypt()) {
      for (String host : knownHosts) {
        final SslConfig sslCfg =
            new SslConfig(
                cfg.letsEncryptPrefix() + host + "/privkey.pem",
                cfg.letsEncryptPrefix() + host + "/fullchain.pem");
        contexts.put(host, createSslContext(sslCfg));
      }
    }
    if (cfg.snis() != null) {
      for (SniConfig sni : cfg.snis()) {
        contexts.put(sni.host(), createSslContext(sni.ssl()));
      }
    }
    SslContext defaultContext = null;
    if (cfg.ssl() != null) {
      defaultContext = createSslContext(cfg.ssl());
    }
    if (defaultContext == null && !contexts.isEmpty()) {
      defaultContext = contexts.sequencedValues().getFirst();
    }
    if (defaultContext == null) {
      throw new IllegalArgumentException("SSL setup missing");
    }
    var builder = new DomainWildcardMappingBuilder<>(defaultContext);
    contexts
        .entrySet()
        .forEach(
            entry -> {
              builder.add(entry.getKey(), entry.getValue());
            });
    return builder.build();
  }

  static SslContext createSslContext(SslConfig cfg) {
    try {
      return SslContextBuilder.forServer(open(cfg.certPath()), open(cfg.keyPath()))
          .clientAuth(ClientAuth.NONE)
          .sslProvider(SslProvider.OPENSSL)
          .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new IllegalArgumentException("IO error", e);
    }
  }

  static InputStream open(String location) throws IOException {
    final String prefix = "classpath:";
    if (location.startsWith(prefix)) {
      String path = location.substring(prefix.length());
      if (path.startsWith("/")) {
        path = path.substring(1);
      }
      InputStream in = Proxy.class.getClassLoader().getResourceAsStream(path);
      if (in == null) {
        throw new FileNotFoundException("Class path resource not found: " + path);
      }
      return in;
    } else {
      return new FileInputStream(location);
    }
  }

  public void start() throws Exception {
    dumpConfig(config);
    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try {
      ServerBootstrap sb = new ServerBootstrap();
      sb.group(bossGroup, eventLoopGroup)
          .channel(NioServerSocketChannel.class)
          // .option(ChannelOption.SO_SNDBUF, 1 * 1024) // testing
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  final ChannelPipeline pipeline = ch.pipeline();
                  pipeline.addLast(trackIngressConnectionsHandler);
                  pipeline.addLast(new SniHandler(sniMapping));
                  pipeline.addLast(new CountByteIoHandler());
                  pipeline.addLast(new HttpServerCodec());
                  addHttpHandlers(pipeline);
                }
              });
      int port = config.listen().httpsPort();
      ChannelFuture f = sb.bind(port).sync();
      System.out.println("Open SSL: " + OpenSsl.versionString());
      System.out.println("Proxy listening on port " + port);
      LOG.trace("tracing enabled");
      f.channel().closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      shutdown();
    }
  }

  void shutdown() {
    eventLoopGroup.shutdownGracefully();
  }

  void addHttpHandlers(ChannelPipeline pipeline) {
    pipeline.addLast(sanitizeHostHandler);
    // logger sits between codec and rest so it sees header modifications
    // from timeout and keep alive below
    // pipeline.addLast(new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE));
    pipeline.addLast(new RequestLoggingHandler(metrics, requestLogger));
    pipeline.addLast(new NewClientTimeoutHandler(connectionConfig, metrics));
    pipeline.addLast(new HttpServerKeepAliveHandler());
    pipeline.addLast(new IpTraitsHandler(ipTraitsLookup));
    //            ch.pipeline().addLast(new ReportIoErrorsHandler("downstream"));
    if (unservicedHostHandler != null) {
      pipeline.addLast(unservicedHostHandler);
    }
    pipeline.addLast("siteSelector", new SiteSelectorHandler(siteSelector));
    pipeline.addLast("protection", dummy404Handler);
    pipeline.addLast("proxy", dummy404Handler);
    pipeline.addLast("exception", new ExceptionHandler(metrics));
  }

  @Override
  public EventLoopGroup eventLoopGroup() {
    return eventLoopGroup;
  }

  @Override
  public ProxyMetrics metrics() {
    return metrics;
  }
}
