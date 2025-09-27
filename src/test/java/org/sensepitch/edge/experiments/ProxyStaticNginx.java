package org.sensepitch.edge.experiments;

import java.util.List;
import java.util.Map;

import org.sensepitch.edge.AdmissionTokenGeneratorConfig;
import org.sensepitch.edge.BypassConfig;
import org.sensepitch.edge.ConnectionConfig;
import org.sensepitch.edge.DeflectorConfig;
import org.sensepitch.edge.ListenConfig;
import org.sensepitch.edge.MetricsConfig;
import org.sensepitch.edge.PrometheusConfig;
import org.sensepitch.edge.ProtectionConfig;
import org.sensepitch.edge.Proxy;
import org.sensepitch.edge.ProxyConfig;
import org.sensepitch.edge.SiteConfig;
import org.sensepitch.edge.SslConfig;
import org.sensepitch.edge.UpstreamConfig;

/**
 * @author Jens Wilke
 */
public class ProxyStaticNginx {

  public static void main(String[] args) throws Exception {
    ProxyConfig cfg =
        ProxyConfig.builder()
            .metrics(
                MetricsConfig.builder()
                    .enable(true)
                    .prometheus(
                        PrometheusConfig.builder().enableJvmMetrics(true).port(9400).build())
                    .build())
            .listen(
                ListenConfig.builder()
                    // testing
                    .connection(ConnectionConfig.DEFAULT.toBuilder()
                      .responseTimeoutSeconds(3)
                      .readTimeoutSeconds(5)
                      .build())
                    .httpsPort(17443)
                    .ssl(
                        SslConfig.builder()
                            .keyPath("performance-test/ssl/nginx.key")
                            .certPath("performance-test/ssl/nginx.crt")
                            .build())
                    .build())
            .upstream(UpstreamConfig.builder().target("172.21.0.2:80").build())
            // not used
            .protection(ProtectionConfig.builder()
              .deflector(DeflectorConfig.builder()
                .tokenGenerators(List.of(
                  AdmissionTokenGeneratorConfig.builder()
                    .secret("secret")
                    .prefix("x")
                    .build()
                ))
                .bypass(BypassConfig.builder()
                  .uris(List.of("/10kb.img"))
                  .build())
                .build())
              .build())
            .sites(
                Map.of(
                    "localhost",
                    SiteConfig.builder()
                        .upstream(UpstreamConfig.builder().target("172.21.0.2:80").build())
                        .build()))
            .build();
    // Proxy.dumpConfig(cfg);
    new Proxy(cfg).start();
  }
}
