package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BindAddressTest {

  /// An address the proxy cannot listen on makes the start fail. `192.0.2.1` is from TEST-NET-1
  /// (RFC 5737) and never assigned to a local interface, so the bind fails without a DNS lookup.
  /// The host name used before, `not-a-valid-address`, depended on the resolver, which may have
  /// let the test hit its timeout once (2026-10-08). Most of the remaining second is the warm-up
  /// of Netty and OpenSSL, when this class runs first in the test JVM.
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void invalidBindAddressThrows() {
    var listenConfig =
        ListenConfig.builder()
            .ssl(
                SslConfig.builder()
                    .keyPath("classpath:ssl/test.key")
                    .certPath("classpath:ssl/test.crt")
                    .build())
            .address("192.0.2.1")
            .build();

    var config =
        ProxyConfig.builder()
            .listen(listenConfig)
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("demo").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();

    var proxy = new Proxy(config, new RequestLogCapture());
    assertThatThrownBy(() -> proxy.start()).isInstanceOf(Exception.class);
  }
}
