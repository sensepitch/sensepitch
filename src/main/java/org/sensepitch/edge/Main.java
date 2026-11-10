package org.sensepitch.edge;

import java.io.FileReader;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

/// @author Jens Wilke
public class Main {

  public static void main(String[] args) throws Exception {
    // route java.util.logging to Log4j2 as well, so libraries using JUL log to the same place.
    // Must happen before anything touches JUL.
    if (System.getProperty("java.util.logging.manager") == null) {
      System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    }
    ProxyConfig config;
    if (args.length > 0) {
      Yaml parser = new Yaml();
      Node root = parser.compose(new FileReader(args[0]));
      config = RecordConstructor.construct(ProxyConfig.class, root);
    } else {
      ProxyConfig.Builder builder = ProxyConfig.builder();
      EnvInjector.injectFromEnv("SENSEPITCH_EDGE_", System.getenv(), builder);
      config = builder.build();
    }
    new Proxy(config).start();
  }
}
