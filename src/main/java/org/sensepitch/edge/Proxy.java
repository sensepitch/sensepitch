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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.sensepitch.edge.config.KeyInjector;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/// Minimal HTTP/1.1 proxy without aggregation, with keep-alive and basic logging.
///
/// Routing, known hosts, certificates and the IP lookup live in an [EdgeState], which
/// [#reload(ProxyConfig)] replaces at runtime. Shutdown budgets also use the active configuration;
/// other settings are fixed at construction.
///
/// A private lock serializes reloads and the start of shutdown. State publication and shutdown
/// completion use atomic references for readers outside that lock. Shutdown drains and waits
/// after releasing it.
public class Proxy implements ProxyContext {

  private static final ProxyLogger LOG = ProxyLogger.get(Proxy.class);

  private final Object lock = new Object();

  /// Monotonic shutdown time and a possibly spurious wait, injected for deterministic delay tests.
  private final LongSupplier shutdownNanoTime;

  private final LongConsumer shutdownWaitNanos;

  private final Dummy404Handler dummy404Handler = new Dummy404Handler();
  private final TrackIngressConnectionsHandler trackIngressConnectionsHandler;
  private final ProxyConfig config;
  private final ConnectionConfig connectionConfig;

  /// Metrics of this proxy, registered also when the export is disabled
  private final PrometheusRegistry metricsRegistry = new PrometheusRegistry();

  /// `null` if the metrics export is disabled
  private final MetricsServer metricsServer;

  // replacements guarded by lock after construction; readers use the atomic reference
  private final AtomicReference<EdgeState> state = new AtomicReference<>();

  /// Reads the known hosts of the current state at every scrape, the state is set before the
  /// metrics server starts
  private final ProxyMetrics metrics = new ProxyMetrics(() -> state.get().knownHosts());

  /// Delegates to the current state, so the SNI handler picks up reloaded certificates
  private final Mapping<String, SslContext> sniMapping = host -> state.get().sniMapping().map(host);

  /// Read once, the event loops are sized by it, see [#environment()]
  private final ProxyEnvironment environment = ProxyEnvironment.current();

  private final EventLoopGroup eventLoopGroup;

  /// Accepts the connections, set by [#start()], closed first on [#shutdown()]. Volatile because
  /// start and shutdown access it without the reload lock, possibly from different threads.
  private volatile EventLoopGroup bossGroup;

  /// Published by `start` and read by shutdown without the reload lock
  private volatile Channel serverChannel;

  /// Completed when the shutdown is done, set under `lock` by the first [#shutdown()] call.
  /// Read without the lock by [#isShuttingDown()].
  private final AtomicReference<CompletableFuture<Void>> shutdown = new AtomicReference<>();

  /// Request log destinations plus the metrics per status code, closed on shutdown
  private final RequestLogger requestLogger;

  /// The request log destinations, replaced on a configuration reload
  private final ReloadableRequestLogger requestLogDestinations;

  /// Metrics shared by all request log destinations, including their circuit breakers, see
  /// [#circuitBreakers()]
  private final RequestLogMetrics requestLogMetrics;

  private final SanitizeHostHandler sanitizeHostHandler =
      new SanitizeHostHandler(() -> state.get().knownHosts());
  private final Ja4Config ja4Config;
  private final Ja4HeaderHandler ja4HeaderHandler;
  private final RequestLogOutput requestLogOutput;
  private final boolean trustTraceparent;

  /// Id of the active configuration, read by the request logging handler per request
  private final Supplier<String> configIds = () -> state.get().configId();

  // guarded by lock
  /// The files the active configuration read, see [#referencedFiles()]
  private Map<Path, ConfigFiles.Fingerprint> referencedFiles;

  /// The files the last rejected configuration tried to read, until a reload is applied
  private Map<Path, ConfigFiles.Fingerprint> candidateFiles = Map.of();

  /// Proxy writing the request log to standard out, see [AsyncRequestLogWriter]
  public Proxy(ProxyConfig config) {
    this(config, AsyncRequestLogWriter.standardOut());
  }

  /// @param requestLogOutput destination of the request log, tests pass a capture here. The proxy
  ///   closes it on [#shutdown()].
  public Proxy(ProxyConfig config, RequestLogOutput requestLogOutput) {
    this(config, requestLogOutput, System::nanoTime, LockSupport::parkNanos);
  }

  /// Timing seam for shutdown tests; production uses the monotonic system clock and park.
  Proxy(
      ProxyConfig config,
      RequestLogOutput requestLogOutput,
      LongSupplier shutdownNanoTime,
      LongConsumer shutdownWaitNanos) {
    this.shutdownNanoTime = shutdownNanoTime;
    this.shutdownWaitNanos = shutdownWaitNanos;
    this.requestLogOutput = requestLogOutput;
    config = KeyInjector.injectAllMapKeys(config);
    if (config.listen() == null) {
      throw new IllegalArgumentException("Missing listen configuration");
    }
    // Validate the entire shutdown budget before allocating runtime resources.
    config.checkShutdownBudget();
    if (config.listen().connection() == null) {
      connectionConfig = ConnectionConfig.DEFAULT;
    } else {
      connectionConfig = config.listen().connection();
    }
    eventLoopGroup =
        new MultiThreadIoEventLoopGroup(environment.eventLoopThreads(), NioIoHandler.newFactory());
    this.config = config;
    ja4Config = config.ja4() != null ? config.ja4() : Ja4Config.DEFAULT;
    // added unconditionally, so a client can never spoof the fingerprint headers
    ja4HeaderHandler = new Ja4HeaderHandler(ja4Config);
    expose(metrics);
    trackIngressConnectionsHandler = expose(new TrackIngressConnectionsHandler());
    expose(new NettyAllocatorMetrics());
    trustTraceparent = config.traceContext() != null && config.traceContext().trustTraceparent();
    ConfigFiles files = new ConfigFiles();
    EdgeState initial = EdgeState.build(this, config, null, files);
    LOG.info("Certificates loaded:" + Certificates.list(initial.certificates()));
    // the destinations share the metrics of the standard out writer, a metric name can be
    // registered only once
    requestLogMetrics =
        requestLogOutput instanceof AsyncRequestLogWriter writer
            ? writer.metrics()
            : new RequestLogMetrics();
    requestLogDestinations =
        RequestLoggerFactory.create(
            config.requestLog(), requestLogOutput, requestLogMetrics, eventLoopGroup, files);
    // after the request log, its secret headers count for the config id
    ConfigFiles.Snapshot snapshot = files.snapshot();
    state.set(initial.withSecrets(snapshot.secretDigests()));
    referencedFiles = snapshot.files();
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
  public ReloadResult reload(ProxyConfig newConfig) {
    synchronized (lock) {
      if (shutdown.get() != null) {
        LOG.info("Configuration reload skipped, shutting down");
        return ReloadResult.rejected("Shutting down");
      }
      EdgeState current = state.get();
      EdgeState next;
      ReloadableRequestLogger.Prepared requestLog;
      ConfigFiles files = new ConfigFiles();
      try {
        newConfig = KeyInjector.injectAllMapKeys(newConfig);
        warnAboutRestartOnlyChanges(newConfig);
        newConfig.checkShutdownBudget();
        next = EdgeState.build(this, newConfig, current, files);
        // last, nothing can fail after it, so the destinations it created are always applied
        requestLog = requestLogDestinations.prepare(newConfig.requestLog(), files);
      } catch (RuntimeException e) {
        LOG.error("Configuration rejected, keeping the active configuration: " + e, e);
        // watched as well, so a fixed file is tried again without touching the configuration
        candidateFiles = files.snapshot().files();
        return ReloadResult.rejected("Configuration rejected: " + e.getMessage());
      }
      ConfigFiles.Snapshot snapshot = files.snapshot();
      next = next.withSecrets(snapshot.secretDigests());
      state.set(next);
      current.closeReplacedBy(next);
      requestLog.apply();
      referencedFiles = snapshot.files();
      candidateFiles = Map.of();
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

  /// The distinct network targets of the active state's upstreams, for the readiness probe. Read
  /// per call, so a reload applies to the next probe.
  public Set<String> upstreamTargets() {
    return state.get().upstreams().networkTargets();
  }

  /// The files the active configuration read: TLS keys and certificates, secrets, response pages
  /// and IP databases, with their fingerprints when they were read. [ConfigFileWatcher] reloads
  /// when one of them changes. After a rejected reload also the files the rejected configuration
  /// tried to read, missing ones included, with the fingerprints it saw, so fixing such a file
  /// retries the reload.
  public Map<Path, ConfigFiles.Fingerprint> referencedFiles() {
    synchronized (lock) {
      if (candidateFiles.isEmpty()) {
        return referencedFiles;
      }
      Map<Path, ConfigFiles.Fingerprint> files = new HashMap<>(referencedFiles);
      files.putAll(candidateFiles);
      return Map.copyOf(files);
    }
  }

  public static void dumpConfig(ProxyConfig proxyConfig) {
    LOG.info("Configuration:\n" + toYaml(proxyConfig));
  }

  /// The configuration as YAML, for the log and the [EdgeState#configId(ProxyConfig, java.util.List)]. The
  /// value of a [SecretConfig] is masked, the config id adds the secrets separately. Without class
  /// tags, so the dump, secrets aside, can be loaded again as configuration.
  static String toYaml(ProxyConfig proxyConfig) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setPrettyFlow(true);
    Representer repr = new MaskingRepresenter(options);
    repr.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
    Yaml yaml = new Yaml(repr, options);
    return yaml.dump(proxyConfig);
  }

  /// Dumps a [SecretConfig] with its value masked and the records as plain mappings, for
  /// [#toYaml(ProxyConfig)]
  private static final class MaskingRepresenter extends Representer {

    MaskingRepresenter(DumperOptions options) {
      super(options);
      representers.put(
          SecretConfig.class,
          data -> {
            SecretConfig secret = (SecretConfig) data;
            Map<String, String> fields = new LinkedHashMap<>();
            if (secret.value() != null) {
              fields.put("value", SecretConfig.MASK);
            } else {
              fields.put("file", secret.file());
            }
            return representMapping(Tag.MAP, fields, DumperOptions.FlowStyle.AUTO);
          });
    }

    /// A plain mapping instead of a global class tag, which the configuration loader rejects
    @Override
    protected MappingNode representJavaBean(Set<Property> properties, Object javaBean) {
      MappingNode node = super.representJavaBean(properties, javaBean);
      node.setTag(Tag.MAP);
      return node;
    }
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
    return initializeSniMapping(cfg, servicedHosts, new LinkedHashMap<>(), new ConfigFiles());
  }

  /// @param certificates receives a description of each loaded certificate by host, see
  ///   [Certificates#describe(SslConfig, ConfigFiles)]
  /// @param files reads the key and certificate files
  static Mapping<String, SslContext> initializeSniMapping(
      ListenConfig cfg,
      Set<String> servicedHosts,
      Map<String, String> certificates,
      ConfigFiles files) {
    var knownHosts = new HashSet<>(servicedHosts);
    if (cfg.hosts() != null) {
      knownHosts.addAll(cfg.hosts());
    }
    var contexts = new LinkedHashMap<String, SslContext>();
    if (cfg.letsEncrypt()) {
      for (String host : knownHosts) {
        final SslConfig sslCfg = cfg.letsEncryptSsl(host);
        contexts.put(host, createSslContext(sslCfg, files));
        certificates.put(host, Certificates.describe(sslCfg, files));
      }
    }
    if (cfg.snis() != null) {
      for (SniConfig sni : cfg.snis()) {
        SanitizeHostHandler.requireValidWildcard(sni.host());
        contexts.put(sni.host(), createSslContext(sni.ssl(), files));
        certificates.put(sni.host(), Certificates.describe(sni.ssl(), files));
      }
    }
    SslContext defaultContext = null;
    if (cfg.ssl() != null) {
      defaultContext = createSslContext(cfg.ssl(), files);
      certificates.put(Certificates.DEFAULT, Certificates.describe(cfg.ssl(), files));
    }
    if (defaultContext == null && !contexts.isEmpty()) {
      defaultContext = contexts.sequencedValues().getFirst();
    }
    if (defaultContext == null) {
      throw new IllegalArgumentException("SSL setup missing");
    }
    // Netty normalizes exact SNI names (including IDNs). Check wildcard matches against the
    // same DNS label rules as HTTP before returning their context.
    SslContext fallback = defaultContext;
    var builder = new DomainWildcardMappingBuilder<Mapping<String, SslContext>>(host -> fallback);
    contexts.forEach(
        (pattern, context) -> {
          if (pattern.startsWith("*.")) {
            builder.add(
                pattern, host -> SanitizeHostHandler.validWildcardLabel(host) ? context : fallback);
          } else {
            builder.add(pattern, host -> context);
          }
        });
    var mapping = builder.build();
    return host -> mapping.map(host).map(host);
  }

  /// @param files reads the key and certificate, see [ConfigFiles#open(String)]
  static SslContext createSslContext(SslConfig cfg, ConfigFiles files) {
    try {
      return SslContextBuilder.forServer(files.open(cfg.certPath()), files.open(cfg.keyPath()))
          .clientAuth(ClientAuth.NONE)
          .sslProvider(SslProvider.OPENSSL)
          .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new IllegalArgumentException("IO error", e);
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
                    pipeline.addLast(new Ja4Handler(metrics));
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

  /// Reports shutdown as soon as it starts, before the listener closes, for admin readiness probes.
  public boolean isShuttingDown() {
    return shutdown.get() != null;
  }

  /// Shuts down gracefully within [ProxyConfig#shutdownTimeoutMillis()], and faster
  /// when everything is done earlier:
  ///
  /// 1. Marks readiness false, then keeps accepting and serving for
  ///    [ProxyConfig#shutdownAcceptGraceMillis()], within the same deadline as the following
  ///    phases.
  /// 2. Stops accepting connections.
  /// 3. Closes idle connections, the others after their response, see
  ///    [TrackIngressConnectionsHandler#drain()]. Waits for that until only the drain timeout
  ///    of the request log is left, see [RequestLogConfig#drainTimeoutMillis()].
  /// 4. Shuts down the event loops, which closes the remaining connections, their requests are
  ///    logged as aborted. Each loop writes its end marker into the request log when it ends.
  /// 5. Waits until the request log destinations sent everything, or the shutdown timeout is
  ///    over.
  ///
  /// A reload in progress completes first, later reloads are rejected, see [#reload(ProxyConfig)].
  /// A phase that runs out of time is logged with what was left. Can be called more than once,
  /// e.g. by the shutdown hook and by [#start()], the later calls wait for the first.
  /// Does not stop process-wide Log4j2: other proxy instances or the embedding application may
  /// still need it. The command-line application [Main] owns logging shutdown.
  public void shutdown() {
    CompletableFuture<Void> done = new CompletableFuture<>();
    CompletableFuture<Void> running;
    // waits for a running reload, see reload()
    synchronized (lock) {
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
    long totalMillis = active.shutdownTimeoutMillis();
    long drainMillis = active.requestLog().drainTimeoutMillis();
    long start = shutdownNanoTime.getAsLong();
    long connectionsDeadline = start + TimeUnit.MILLISECONDS.toNanos(totalMillis - drainMillis);
    long deadline = start + TimeUnit.MILLISECONDS.toNanos(totalMillis);
    awaitAcceptGrace(start + TimeUnit.MILLISECONDS.toNanos(active.shutdownAcceptGraceMillis()));
    if (serverChannel != null) {
      serverChannel.close().syncUninterruptibly();
      bossGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }
    if (!trackIngressConnectionsHandler
        .drain()
        .awaitUninterruptibly(remainingMillis(connectionsDeadline))) {
      LOG.info(
          "Shutdown: requests still in flight after "
              + TimeUnit.NANOSECONDS.toMillis(shutdownNanoTime.getAsLong() - start)
              + " ms, total "
              + metrics.ingressRequestsInFlight()
              + ", by upstream "
              + metrics.upstreamRequestsInFlight()
              + ", closing the connections");
    }
    long drainStart = shutdownNanoTime.getAsLong();
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
              + TimeUnit.NANOSECONDS.toMillis(shutdownNanoTime.getAsLong() - drainStart)
              + " ms, unsent records "
              + requestLogDestinations.unsent());
      if (!eventLoopGroup.isTerminated()) {
        LOG.info("Shutdown: event loop group still running");
      }
    }
    requestLogOutput.close();
    if (metricsServer != null) {
      metricsServer.close();
    }
  }

  /// Keeps the listener open through propagation, tolerating spurious wakeups and interruption.
  /// Like the other shutdown waits, this restores interruption only after the wait finishes.
  private void awaitAcceptGrace(long deadlineNanos) {
    boolean interrupted = Thread.interrupted();
    try {
      long remaining;
      while ((remaining = deadlineNanos - shutdownNanoTime.getAsLong()) > 0) {
        shutdownWaitNanos.accept(remaining);
        interrupted |= Thread.interrupted();
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private long remainingMillis(long deadlineNanos) {
    return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - shutdownNanoTime.getAsLong()));
  }

  private static final StripEdgeHeadersHandler STRIP_EDGE_HEADERS_HANDLER =
      new StripEdgeHeadersHandler();

  void addHttpHandlers(ChannelPipeline pipeline) {
    pipeline.addLast(STRIP_EDGE_HEADERS_HANDLER);
    pipeline.addLast(sanitizeHostHandler);
    // logger sits between codec and rest so it sees header modifications
    // from the timeout and keep-alive handling below
    pipeline.addLast(
        new RequestLoggingHandler(metrics, requestLogger, trustTraceparent, configIds));
    pipeline.addLast(new IngressTimeoutHandler(connectionConfig, metrics));
    pipeline.addLast(new IpTraitsHandler(state.get().ipTraitsLookup()));
    pipeline.addLast(ja4HeaderHandler);
    // Resolve these per request so reloads apply to existing keep-alive connections.
    pipeline.addLast(new UnservicedHost.Handler(() -> state.get().unservicedHost()));
    pipeline.addLast("siteSelector", new SiteSelectorHandler(() -> state.get().siteSelector()));
    pipeline.addLast("fallback", dummy404Handler);
    pipeline.addLast("protection", dummy404Handler);
    pipeline.addLast("proxy", dummy404Handler);
    pipeline.addLast("exception", new ExceptionHandler(metrics));
  }

  /// The circuit breakers of the request log destinations, for the admin endpoint that forces
  /// a breaker open or closed for maintenance. There are no upstream breakers yet, see
  /// [CircuitBreaker].
  CircuitBreakerMetrics circuitBreakers() {
    return requestLogMetrics.circuitBreakers;
  }

  @Override
  public EventLoopGroup eventLoopGroup() {
    return eventLoopGroup;
  }

  @Override
  public ProxyMetrics metrics() {
    return metrics;
  }

  @Override
  public ProxyEnvironment environment() {
    return environment;
  }
}
