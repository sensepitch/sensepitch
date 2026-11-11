package org.sensepitch.edge;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

/// Starts the proxy with the YAML configuration file given as first argument, or with the
/// configuration from environment variables.
///
/// A configuration file is reloaded when its content changes, and via `POST /reload` on the
/// [AdminServer] if `admin` is configured, if it ends with the line `#END`, see
/// [ConfigFileWatcher]. The admin endpoint also reloads an unchanged configuration, which picks up
/// renewed certificate files.
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
      watcher.start(content -> proxy.reload(parseConfig(content)));
      reload = watcher::forceReload;
    } else {
      ProxyConfig.Builder builder = ProxyConfig.builder();
      EnvInjector.injectFromEnv("SENSEPITCH_EDGE_", System.getenv(), builder);
      config = builder.build();
      proxy = new Proxy(config);
    }
    AdminServer admin = config.admin() != null ? AdminServer.start(config.admin(), reload) : null;
    try {
      proxy.start();
    } finally {
      // its threads are no daemon threads and would keep the JVM running
      if (admin != null) {
        admin.stop();
      }
    }
  }

  static ProxyConfig loadConfig(Path file) {
    try (Reader reader = Files.newBufferedReader(file)) {
      return construct(new Yaml().compose(reader));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ProxyConfig parseConfig(String yaml) {
    return construct(new Yaml().compose(new StringReader(yaml)));
  }

  private static ProxyConfig construct(Node root) {
    return RecordConstructor.construct(ProxyConfig.class, root);
  }
}
