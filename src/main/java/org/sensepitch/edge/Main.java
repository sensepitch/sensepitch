package org.sensepitch.edge;

import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;

import java.io.FileReader;

/**
 * @author Jens Wilke
 */
public class Main {

  public static void main(String[] args) throws Exception {
    ProxyConfig config;
    if (args.length > 0) {
      Yaml parser = new Yaml();
      Node root =  parser.compose(new FileReader(args[0]));
      config = RecordConstructor.construct(ProxyConfig.class, root);
    } else {
      ProxyConfig.Builder builder = ProxyConfig.builder();
      EnvInjector.injectFromEnv("SENSEPITCH_EDGE_", System.getenv(), builder);
      config = builder.build();
    }
    new Proxy(config).start();
  }

}
