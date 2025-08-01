package org.sensepitch.edge.experiments;

import org.sensepitch.edge.AdmissionConfig;
import org.sensepitch.edge.AdmissionTokenGeneratorConfig;
import org.sensepitch.edge.GeoIp2Config;
import org.sensepitch.edge.IpLookupConfig;
import org.sensepitch.edge.ListenConfig;
import org.sensepitch.edge.MetricsConfig;
import org.sensepitch.edge.PrometheusConfig;
import org.sensepitch.edge.Proxy;
import org.sensepitch.edge.ProxyConfig;
import org.sensepitch.edge.SniConfig;
import org.sensepitch.edge.SslConfig;
import org.sensepitch.edge.UpstreamConfig;

import java.util.List;

/**
 * @author Jens Wilke
 */
public class ProxyLocalPrestashop {

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
        .httpsPort(7443)
        .snis(List.of(SniConfig.builder()
          .host("ps90.packingpanic.com")
          .ssl(SslConfig.builder()
            .keyPath(System.getenv("HOME") + "/proj/local-ca/live/ps90.packingpanic.com.key")
            .certPath(System.getenv("HOME") + "/proj/local-ca/live/ps90.packingpanic.com.crt")
            .build())
          .build()))
        .build())
      .ipLookup(IpLookupConfig.builder()
        .geoIp2(GeoIp2Config.builder()
          .asnDb(System.getenv("HOME") + "/proj/maxmind-geolite2/GeoLite2-ASN-latest/GeoLite2-ASN.mmdb")
          .build())
        .build())
      .upstream(List.of(
        UpstreamConfig.builder()
          .host("ps90.packingpanic.com")
          // .target("172.24.0.2:80")
          .target("10.76.90.254:80")
          .build()
      ))
      .admission(AdmissionConfig.builder()
        .tokenGenerator(List.of(
          AdmissionTokenGeneratorConfig.builder()
            .prefix("X")
            .secret("secret")
            .build()
        ))
        .build())
      .build();
    new Proxy(cfg).start();
  }


}
