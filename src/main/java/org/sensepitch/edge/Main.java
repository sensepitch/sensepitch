package org.sensepitch.edge;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

/// Starts the proxy with the YAML configuration file given as first argument, or with the
/// configuration from environment variables.
///
/// A configuration file is reloaded when its content changes, and via `POST /reload` on the
/// [AdminServer] if `admin` is configured, if it ends with the line `#END`, see
/// [ConfigFileWatcher]. The admin endpoint also reloads an unchanged configuration, which picks up
/// renewed certificate files. The watcher also reloads when a file the configuration read changes,
/// e.g. a TLS certificate or a secret, see [Proxy#referencedFiles()].
///
/// The command-line application owns process-wide logging. Its shutdown hook waits for its proxy
/// and admin server before stopping Log4j2; the bundled logging configurations disable Log4j2's
/// concurrent hook. Embedded proxies leave logging lifecycle ownership to their caller.
///
/// @author Jens Wilke
public class Main {

  private Main() {}

  public static void main(String[] args) throws Exception {
    // route java.util.logging to Log4j2 as well, so libraries using JUL log to the same place.
    // Must happen before anything touches JUL.
    if (System.getProperty("java.util.logging.manager") == null) {
      System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    }
    ProxyConfig config;
    Proxy proxy;
    Supplier<ReloadResult> reload = null;
    if (args.length > 0) {
      Path file = Path.of(args[0]);
      // before loading, so a change during startup is not missed
      ConfigFileWatcher watcher = new ConfigFileWatcher(file);
      config = loadConfig(file);
      proxy = new Proxy(config);
      watcher.start(content -> proxy.reload(parseConfig(content)), proxy::referencedFiles);
      reload = watcher::forceReload;
    } else {
      config =
          (ProxyConfig)
              EnvInjector.injectFromEnv(
                  "SENSEPITCH_EDGE_",
                  System.getenv(),
                  ProxyEnvironment.current(),
                  ProxyConfig.builder());
      proxy = new Proxy(config);
    }
    AdminServer admin =
        config.admin() != null
            ? AdminServer.start(
                config.admin(),
                reload,
                proxy::isShuttingDown,
                new UpstreamReadinessProbe(proxy::upstreamTargets)::isReady,
                proxy.circuitBreakers())
            : null;
    Shutdown shutdown =
        new Shutdown(
            () -> {
              try {
                proxy.shutdown();
              } finally {
                // its threads are not daemon threads and would keep the JVM running
                if (admin != null) {
                  admin.stop();
                }
              }
            },
            LogManager::shutdown);
    Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "main-shutdown"));
    try {
      proxy.start();
    } finally {
      shutdown.run();
    }
  }

  /// Orders application cleanup on normal exit and SIGTERM. Concurrent callers wait for the same
  /// cleanup, including logging, and each step runs once even if service cleanup fails.
  static final class Shutdown implements Runnable {
    private final Object lock = new Object();
    private final Runnable stopServices;
    private final Runnable stopLogging;
    // guarded by lock
    private boolean stopped;

    Shutdown(Runnable stopServices, Runnable stopLogging) {
      this.stopServices = stopServices;
      this.stopLogging = stopLogging;
    }

    @Override
    public void run() {
      synchronized (lock) {
        if (stopped) {
          return;
        }
        stopped = true;
        try {
          stopServices.run();
        } finally {
          stopLogging.run();
        }
      }
    }
  }

  static ProxyConfig loadConfig(Path file) {
    try (Reader reader = Files.newBufferedReader(file)) {
      return construct(new Yaml().compose(reader), ProxyEnvironment.current());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ProxyConfig parseConfig(String yaml) {
    return parseConfig(yaml, ProxyEnvironment.current());
  }

  /// Parses the configuration, defaults that depend on the environment are taken from
  /// `environment`, e.g. a fixed one in tests
  static ProxyConfig parseConfig(String yaml, ProxyEnvironment environment) {
    return construct(new Yaml().compose(new StringReader(yaml)), environment);
  }

  private static ProxyConfig construct(Node root, ProxyEnvironment environment) {
    return RecordConstructor.construct(ProxyConfig.class, root, environment);
  }
}
