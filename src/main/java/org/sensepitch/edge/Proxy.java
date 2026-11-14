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
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.sensepitch.edge.config.KeyInjector;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.representer.Representer;

/// Minimal HTTP/1.1 proxy without aggregation, with keep-alive and basic logging.
///
/// Routing, known hosts, certificates and the IP lookup live in an [EdgeState], which
/// [#reload(ProxyConfig)] replaces at runtime. Everything else is fixed at construction.
public class Proxy implements ProxyContext {

  private static final ProxyLogger LOG = ProxyLogger.get(Proxy.class);

  private final Dummy404Handler dummy404Handler = new Dummy404Handler();
  private final TrackIngressConnectionsHandler trackIngressConnectionsHandler;
  private final ProxyMetrics metrics = new ProxyMetrics();
  private final ProxyConfig config;
  private final ConnectionConfig connectionConfig;

  /// Metrics of this proxy, registered also when the export is disabled
  private final PrometheusRegistry metricsRegistry = new PrometheusRegistry();

  /// `null` if the metrics export is disabled
  private final MetricsServer metricsServer;

  private final AtomicReference<EdgeState> state = new AtomicReference<>();

  /// Delegates to the current state, so the SNI handler picks up reloaded certificates
  private final Mapping<String, SslContext> sniMapping = host -> state.get().sniMapping().map(host);

  private final EventLoopGroup eventLoopGroup;

  /// Accepts the connections, set by [#start()], closed first on [#shutdown()]
  private volatile EventLoopGroup bossGroup;

  private volatile Channel serverChannel;

  /// Completed when the shutdown is done, set by the first [#shutdown()] call
  private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();

  /// Request log destinations plus the metrics per status code, closed on shutdown
  private final RequestLogger requestLogger;

  /// The request log destinations, replaced on a configuration reload
  private final ReloadableRequestLogger requestLogDestinations;

  private final SanitizeHostHandler sanitizeHostHandler =
      new SanitizeHostHandler(() -> state.get().knownHosts());
  private final Ja4Config ja4Config;
  private final Ja4HeaderHandler ja4HeaderHandler;
  private final RequestLogOutput requestLogOutput;
  private final boolean trustTraceparent;

  /// Id of the active configuration, read by the request logging handler per request
  private final Supplier<String> configIds = () -> state.get().configId();

  /// Proxy writing the request log to standard out, see [AsyncRequestLogWriter]
  public Proxy(ProxyConfig config) {
    this(config, AsyncRequestLogWriter.standardOut());
  }

  /// @param requestLogOutput destination of the request log, tests pass a capture here. The proxy
  ///   closes it on [#shutdown()].
  public Proxy(ProxyConfig config, RequestLogOutput requestLogOutput) {
    this.requestLogOutput = requestLogOutput;
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
    ja4Config = config.ja4() != null ? config.ja4() : Ja4Config.DEFAULT;
    // added unconditionally, so a client can never spoof the fingerprint headers
    ja4HeaderHandler = new Ja4HeaderHandler(ja4Config);
    expose(metrics);
    trackIngressConnectionsHandler = expose(new TrackIngressConnectionsHandler());
    expose(new NettyAllocatorMetrics());
    trustTraceparent = config.traceContext() != null && config.traceContext().trustTraceparent();
    state.set(EdgeState.build(this, config, null));
    LOG.info("Certificates loaded:" + Certificates.list(state.get().certificates()));
    // the destinations share the metrics of the standard out writer, a metric name can be
    // registered only once
    RequestLogMetrics requestLogMetrics =
        requestLogOutput instanceof AsyncRequestLogWriter writer
            ? writer.metrics()
            : new RequestLogMetrics();
    // validates the shutdown timeouts
    config.effectiveDrainTimeoutMillis();
    requestLogDestinations =
        RequestLoggerFactory.create(
            config.requestLog(), requestLogOutput, requestLogMetrics, eventLoopGroup);
    expose(requestLogMetrics);
    requestLogger =
        new DistributingRequestLogger(
            requestLogDestinations, expose(new ExposeRequestCountPerStatusCodeHandler()));
    // last, so a configuration error does not leave a running server behind
    metricsServer = startMetricsServer();
  }

  /// Activates a new configuration. Routing, known hosts, certificates, the IP lookup and the
  /// request log destinations are replaced; unchanged upstreams, an unchanged IP lookup and
  /// unchanged request log destinations are kept. Changes of settings that
  /// need a restart are logged as a warning and ignored. Requests on keep-alive connections use
  /// the new configuration from their next request on.
  ///
  /// A reload and the start of the shutdown run one at a time: a shutdown waits for a running
  /// reload, a reload after the shutdown started is rejected.
  ///
  /// @return applied, or rejected with the reason if the configuration is invalid or the proxy
  ///   shuts down. The error is logged and the current configuration stays active.
  public synchronized ReloadResult reload(ProxyConfig newConfig) {
    if (shutdown.get() != null) {
      LOG.info("Configuration reload skipped, shutting down");
      return ReloadResult.rejected("Shutting down");
    }
    EdgeState current = state.get();
    EdgeState next;
    ReloadableRequestLogger.Prepared requestLog;
    try {
      newConfig = KeyInjector.injectAllMapKeys(newConfig);
      warnAboutRestartOnlyChanges(newConfig);
      newConfig.effectiveDrainTimeoutMillis();
      next = EdgeState.build(this, newConfig, current);
      // last, nothing can fail after it, so the destinations it created are always applied
      requestLog = requestLogDestinations.prepare(newConfig.requestLog());
    } catch (RuntimeException e) {
      LOG.error("Configuration rejected, keeping the active configuration: " + e, e);
      return ReloadResult.rejected("Configuration rejected: " + e.getMessage());
    }
    state.set(next);
    current.closeReplacedBy(next);
    requestLog.apply();
    String diff = ConfigDiff.diff(toYaml(current.config()), toYaml(next.config()));
    if (diff.isEmpty()) {
      LOG.info("Configuration reloaded, no changes");
    } else {
      LOG.info("Configuration reloaded, changes:\n" + diff);
    }
    String certificateChanges = Certificates.changes(current.certificates(), next.certificates());
    // unchanged certificates are the common case, the configuration line above covers the reload
    if (!certificateChanges.isEmpty()) {
      LOG.info("Certificates reloaded, changes:" + certificateChanges);
    }
    return ReloadResult.APPLIED;
  }

  private void warnAboutRestartOnlyChanges(ProxyConfig cfg) {
    if (cfg.listen() == null) {
      throw new IllegalArgumentException("Missing listen configuration");
    }
    ListenConfig was = config.listen();
    ListenConfig now = cfg.listen();
    warnIfChanged("listen.address", was.address(), now.address());
    warnIfChanged("listen.httpsPort", was.httpsPort(), now.httpsPort());
    warnIfChanged("listen.connection", was.connection(), now.connection());
    warnIfChanged("metrics", config.metrics(), cfg.metrics());
    warnIfChanged("ja4", config.ja4(), cfg.ja4());
    warnIfChanged("traceContext", config.traceContext(), cfg.traceContext());
    warnIfChanged("admin", config.admin(), cfg.admin());
  }

  private static void warnIfChanged(String key, Object was, Object now) {
    if (!Objects.equals(was, now)) {
      LOG.warn("Configuration change of '" + key + "' needs a restart, ignored");
    }
  }

  /// The currently active state, for tests
  EdgeState state() {
    return state.get();
  }

  public static void dumpConfig(ProxyConfig proxyConfig) {
    LOG.info("Configuration:\n" + toYaml(proxyConfig));
  }

  static String toYaml(ProxyConfig proxyConfig) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setPrettyFlow(true);
    Representer repr = new Representer(options);
    repr.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
    Yaml yaml = new Yaml(repr, options);
    return yaml.dump(proxyConfig);
  }

  /// Registers the collectors in the [#metricsRegistry()]
  private <T extends HasMultipleMetrics> T expose(T objectWithMetrics) {
    objectWithMetrics.registerCollectors(metricsRegistry::register);
    return objectWithMetrics;
  }

  /// @return the started server or `null`, if the metrics export is disabled
  private MetricsServer startMetricsServer() {
    MetricsConfig metricsConfig = config.metrics();
    if (metricsConfig == null) {
      metricsConfig = MetricsConfig.DEFAULT;
    }
    if (!metricsConfig.enable()) {
      return null;
    }
    PrometheusConfig prometheusConfig = metricsConfig.prometheus();
    if (prometheusConfig == null) {
      prometheusConfig = PrometheusConfig.DEFAULT;
    }
    return MetricsServer.start(prometheusConfig, metricsRegistry);
  }

  /// All metrics of this proxy, also if the export is disabled
  PrometheusRegistry metricsRegistry() {
    return metricsRegistry;
  }

  static Mapping<String, SslContext> initializeSniMapping(
      ListenConfig cfg, Set<String> servicedHosts) {
    return initializeSniMapping(cfg, servicedHosts, new LinkedHashMap<>());
  }

  /// @param certificates receives a description of each loaded certificate by host, see
  ///   [Certificates#describe(SslConfig)]
  static Mapping<String, SslContext> initializeSniMapping(
      ListenConfig cfg, Set<String> servicedHosts, Map<String, String> certificates) {
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
        certificates.put(host, Certificates.describe(sslCfg));
      }
    }
    if (cfg.snis() != null) {
      for (SniConfig sni : cfg.snis()) {
        contexts.put(sni.host(), createSslContext(sni.ssl()));
        certificates.put(sni.host(), Certificates.describe(sni.ssl()));
      }
    }
    SslContext defaultContext = null;
    if (cfg.ssl() != null) {
      defaultContext = createSslContext(cfg.ssl());
      certificates.put(Certificates.DEFAULT, Certificates.describe(cfg.ssl()));
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
    bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try {
      ServerBootstrap sb = new ServerBootstrap();
      // deliberately no allocator is set, here or in DefaultUpstream, so both sides use
      // ByteBufAllocator.DEFAULT. That is netty's AdaptiveByteBufAllocator, whose javadoc still
      // calls it experimental, so watch sensepitch_netty_allocator_used_memory_bytes. To pin a
      // different one, -Dio.netty.allocator.type switches it globally, or use childOption() here
      // for the accepted connections; option() would only cover the listening socket.
      sb.group(bossGroup, eventLoopGroup)
          .channel(NioServerSocketChannel.class)
          // .option(ChannelOption.SO_SNDBUF, 1 * 1024) // testing
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  final ChannelPipeline pipeline = ch.pipeline();
                  pipeline.addLast(trackIngressConnectionsHandler);
                  if (ja4Config.enable()) {
                    // must see the raw ClientHello, so before TLS is terminated
                    pipeline.addLast(new Ja4Handler());
                  }
                  pipeline.addLast(new SniHandler(sniMapping));
                  pipeline.addLast(new CountByteIoHandler());
                  pipeline.addLast(new HttpServerCodec());
                  addHttpHandlers(pipeline);
                }
              });
      String addr = config.listen().address();
      int port = config.listen().httpsPort();
      ChannelFuture f = sb.bind(new InetSocketAddress(addr, port)).sync();
      serverChannel = f.channel();
      LOG.info("Open SSL: " + OpenSsl.versionString());
      LOG.info("Proxy listening on " + addr + ":" + port);
      LOG.trace("tracing enabled");
      // when the JVM is stopped, e.g. by SIGTERM, the JVM exits when the hook returns
      Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown"));
      f.channel().closeFuture().sync();
    } finally {
      shutdown();
    }
  }

  /// Shuts down gracefully within [ProxyConfig#effectiveShutdownTimeoutMillis()], and faster
  /// when everything is done earlier:
  ///
  /// 1. Stops accepting connections.
  /// 2. Closes idle connections, the others after their response, see
  ///    [TrackIngressConnectionsHandler#drain()]. Waits for that until only the drain timeout
  ///    of the request log is left, see [ProxyConfig#effectiveDrainTimeoutMillis()].
  /// 3. Shuts down the event loops, which closes the remaining connections, their requests are
  ///    logged as aborted. Each loop writes its end marker into the request log when it ends.
  /// 4. Waits until the request log destinations sent everything, or the shutdown timeout is
  ///    over.
  ///
  /// A reload in progress completes first, later reloads are rejected, see [#reload(ProxyConfig)].
  /// A phase that runs out of time is logged with what was left. Can be called more than once,
  /// e.g. by the shutdown hook and by [#start()], the later calls wait for the first.
  public void shutdown() {
    CompletableFuture<Void> done = new CompletableFuture<>();
    CompletableFuture<Void> running;
    // waits for a running reload, see reload()
    synchronized (this) {
      running = shutdown.compareAndExchange(null, done);
    }
    if (running != null) {
      running.join();
      return;
    }
    try {
      shutdownOnce();
    } finally {
      done.complete(null);
    }
  }

  private void shutdownOnce() {
    ProxyConfig active = state.get().config();
    long totalMillis = active.effectiveShutdownTimeoutMillis();
    long drainMillis = active.effectiveDrainTimeoutMillis();
    long start = System.nanoTime();
    long connectionsDeadline = start + TimeUnit.MILLISECONDS.toNanos(totalMillis - drainMillis);
    long deadline = start + TimeUnit.MILLISECONDS.toNanos(totalMillis);
    if (serverChannel != null) {
      serverChannel.close().syncUninterruptibly();
      bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }
    if (!trackIngressConnectionsHandler
        .drain()
        .awaitUninterruptibly(remainingMillis(connectionsDeadline))) {
      LOG.info(
          "Shutdown: requests still in flight after "
              + (totalMillis - drainMillis)
              + " ms, total "
              + metrics.ingressRequestsInFlight()
              + ", by upstream "
              + metrics.upstreamRequestsInFlight()
              + ", closing the connections");
    }
    // the calling thread may have logged as well, e.g. a test driving an EmbeddedChannel
    requestLogDestinations.endThread();
    CompletableFuture<Void> loops = new CompletableFuture<>();
    eventLoopGroup
        .shutdownGracefully(0, remainingMillis(deadline), TimeUnit.MILLISECONDS)
        .addListener(future -> loops.complete(null));
    // finished after the loops ended: a loop that logs for the first time while closing its
    // connections still gets a batcher
    boolean drained =
        loops
            .thenCompose(done -> requestLogDestinations.finish())
            .thenApply(v -> true)
            .completeOnTimeout(false, remainingMillis(deadline), TimeUnit.MILLISECONDS)
            .join();
    if (!drained) {
      LOG.info(
          "Shutdown: request log not drained after "
              + drainMillis
              + " ms, unsent records "
              + requestLogDestinations.unsent());
    }
    requestLogOutput.close();
    if (metricsServer != null) {
      metricsServer.close();
    }
  }

  private static long remainingMillis(long deadlineNanos) {
    return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
  }

  private static final StripEdgeHeadersHandler STRIP_EDGE_HEADERS_HANDLER =
      new StripEdgeHeadersHandler();

  void addHttpHandlers(ChannelPipeline pipeline) {
    pipeline.addLast(sanitizeHostHandler);
    pipeline.addLast(STRIP_EDGE_HEADERS_HANDLER);
    // logger sits between codec and rest so it sees header modifications
    // from timeout and keep alive below
    pipeline.addLast(
        new RequestLoggingHandler(metrics, requestLogger, trustTraceparent, configIds));
    pipeline.addLast(new IngressTimeoutHandler(connectionConfig, metrics));
    pipeline.addLast(new HttpServerKeepAliveHandler());
    pipeline.addLast(new IpTraitsHandler(() -> state.get().ipTraitsLookup()));
    pipeline.addLast(ja4HeaderHandler);
    pipeline.addLast(new UnservicedHost.Handler(() -> state.get().unservicedHost()));
    pipeline.addLast("siteSelector", new SiteSelectorHandler(() -> state.get().siteSelector()));
    pipeline.addLast("fallback", dummy404Handler);
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
