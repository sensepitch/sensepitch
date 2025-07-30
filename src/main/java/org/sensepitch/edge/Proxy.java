package org.sensepitch.edge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.representer.Representer;

import javax.net.ssl.SSLException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal HTTP/1.1 proxy without aggregation, with keep-alive and basic logging
 */
public class Proxy implements ProxyContext {

  ProxyLogger LOG = ProxyLogger.get(Proxy.class);

  private final Dummy404Handler dummy404Handler = new Dummy404Handler();
  private final TrackIngressConnectionsHandler trackIngressConnectionsHandler;
  private final ProxyMetrics metrics = new ProxyMetrics();
  private final ProxyConfig config;
  private final ConnectionConfig connectionConfig;
  private final MetricsBridge metricsBridge;
  private final AdmissionHandler admissionHandler;
  private final SslContext sslContext;
  private final Mapping<String, SslContext> sniMapping;
  private final UnservicedHostHandler unservicedHostHandler;
  // private final DownstreamHandler downstreamHandler;
  // private final UpstreamRouter upstreamRouter;
  private final IpTraitsLookup ipTraitsLookup;
  private final EventLoopGroup eventLoopGroup;
  private final RequestLogger requestLogger;
  private final SanitizeHostHandler sanitizeHostHandler;
  private final SiteSelectorHandler siteSelectorHandler;

  public Proxy(ProxyConfig config) {
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
    trackIngressConnectionsHandler = metricsBridge.expose(new  TrackIngressConnectionsHandler());
    if (config.admission() != null) {
      admissionHandler = metricsBridge.expose(new AdmissionHandler(config.admission()));
    } else {
      admissionHandler = null;
    }
    sslContext = initializeSslContext();
    sniMapping = initializeSniMapping();
    siteSelectorHandler = new SiteSelectorHandler(this, config);
    Set<String> servicedHosts = siteSelectorHandler.getServicedHosts();
    Set<String> knownHosts = new HashSet<String>();
    knownHosts.addAll(servicedHosts);
    if (config.listen().hosts() != null) {
      knownHosts.addAll(config.listen().hosts());
    }
    UnservicedHostConfig unservicedHostConfig = config.unservicedHost() != null ? config.unservicedHost() : UnservicedHostConfig.builder().build();
    if (unservicedHostConfig.servicedDomains() == null) {
      unservicedHostConfig =
        unservicedHostConfig.toBuilder()
          .servicedDomains(servicedHosts)
          .build();
    }
    unservicedHostHandler = new UnservicedHostHandler(unservicedHostConfig);
    sanitizeHostHandler = new SanitizeHostHandler(knownHosts);
    requestLogger = new DistributingRequestLogger(
      new StandardOutRequestLogger(),
      metricsBridge.expose(new ExposeRequestCountPerStatusCodeHandler()));
    try {
      if (config.ipLookup() != null) {
        ipTraitsLookup = new CombinedIpTraitsLookup(config.ipLookup());
      } else {
        ipTraitsLookup = (builder, address) -> { };
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void dumpConfig(ProxyConfig proxyConfig) {
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

  SslContext initializeSslContext() {
    if (config.listen().ssl() == null) {
      return null;
    }
    return createSslContext(config.listen().ssl());
  }

  Mapping<String, SslContext> initializeSniMapping() {
    var domains = config.listen().hosts();
    var snis = config.listen().sni();
    var defaultContext = initializeSslContext();
    if (defaultContext == null && snis != null) {
      defaultContext = createSslContext(snis.getFirst().ssl());
    }
    if (defaultContext == null) {
      throw new IllegalArgumentException("SSL setup missing");
    }
    var builder = new DomainWildcardMappingBuilder<>(defaultContext);
    if (domains != null) {
      for (String domain : domains) {
        String filePrefix = "/etc/letsencrypt/live/";
        builder.add(domain, createSslContext(new SslConfig(
          filePrefix + domain + "/privkey.pem",
          filePrefix + domain + "/fullchain.pem")));
      }
    }
    if (snis != null) {
      snis.forEach(sni -> builder.add(sni.domain(), createSslContext(sni.ssl())));
    }
    return builder.build();
  }

  SslContext createSslContext(SslConfig cfg) {
    try {
      return SslContextBuilder.forServer(open(cfg.cert()), open(cfg.key()))
        .clientAuth(ClientAuth.NONE)
        .sslProvider(SslProvider.OPENSSL)
        .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new IllegalArgumentException("file not found", e);
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
        .childHandler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            final ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(trackIngressConnectionsHandler);
            if (sniMapping != null) {
              pipeline.addLast(new SniHandler(sniMapping));
            } else if (sslContext != null) {
              pipeline.addLast(sslContext.newHandler(ch.alloc()));
            }
            pipeline.addLast(new HttpServerCodec());
            addHttpHandlers(pipeline);
          }
        });
      int port = config.listen().port();
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
    pipeline.addLast(new RequestLoggingHandler(requestLogger));
    pipeline.addLast(new ClientTimeoutHandler(connectionConfig, metrics));
    pipeline.addLast(new HttpServerKeepAliveHandler());
    // ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE));
    pipeline.addLast(new IpTraitsHandler(ipTraitsLookup));
//            ch.pipeline().addLast(new ReportIoErrorsHandler("downstream"));
    if (unservicedHostHandler != null) {
      pipeline.addLast(unservicedHostHandler);
    }
    pipeline.addLast("siteSelector", siteSelectorHandler);
    pipeline.addLast("protection", dummy404Handler);
    pipeline.addLast("proxy", dummy404Handler);
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

