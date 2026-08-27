package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BindAddressTest {

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
            .address("not-a-valid-address")
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

    var proxy = new Proxy(config);
    assertThatThrownBy(() -> proxy.start()).isInstanceOf(Exception.class);
  }
}
