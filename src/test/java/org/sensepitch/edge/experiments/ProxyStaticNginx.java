package org.sensepitch.edge.experiments;

import org.sensepitch.edge.AdmissionConfig;
import org.sensepitch.edge.AdmissionTokenGeneratorConfig;
import org.sensepitch.edge.BypassConfig;
import org.sensepitch.edge.ConnectionConfig;
import org.sensepitch.edge.ListenConfig;
import org.sensepitch.edge.MetricsConfig;
import org.sensepitch.edge.PrometheusConfig;
import org.sensepitch.edge.ProtectionConfig;
import org.sensepitch.edge.Proxy;
import org.sensepitch.edge.ProxyConfig;
import org.sensepitch.edge.SiteConfig;
import org.sensepitch.edge.SslConfig;
import org.sensepitch.edge.UpstreamConfig;

import java.util.List;
import java.util.Map;

/**
 * @author Jens Wilke
 */
public class ProxyStaticNginx {

  public static void main(String[] args) throws Exception {
    ProxyConfig cfg = ProxyConfig.builder()
      .metrics(MetricsConfig.builder()
        .enable(true)
        .prometheus(PrometheusConfig.builder()
          .enableJvmMetrics(true)
          .port(9400)
          .build())
        .build())
      .listen(ListenConfig.builder()
        // testing
        .connection(ConnectionConfig.DEFAULT.toBuilder()
          .readTimeoutSeconds(3)
          .build())
        .httpsPort(7443)
        .ssl(SslConfig.builder()
          .keyPath("performance-test/ssl/nginx.key")
          .certPath("performance-test/ssl/nginx.crt")
          .build())
        .build())
      .upstream(List.of(
        UpstreamConfig.builder()
          .host("")
          .target("172.21.0.3:80")
          .build()
      ))
      .admission(AdmissionConfig.builder()
        .bypass(BypassConfig.builder()
          .uriPrefixes(List.of("/"))
          .build())
        .tokenGenerator(List.of(
          AdmissionTokenGeneratorConfig.builder()
            .prefix("X")
            .secret("secret")
            .build()
        ))
        .build())
      .sites(Map.of(
          "any", SiteConfig.builder()
            .host("*")
            .protection(ProtectionConfig.builder().build())
            .upstream(
              UpstreamConfig.builder()
                .target("172.21.0.3:80")
                .build()
            )
            .build()
        ))
      .build();
    Proxy.dumpConfig(cfg);
    new Proxy(cfg).start();
  }

}
