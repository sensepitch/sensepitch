package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// Defaults of the shutdown timeouts, the rejected values are in [ProxyRejectedConfigBDDTest]
///
/// @author Jens Wilke
class ShutdownTimeoutTest {

  @Test
  void drainTimeoutDefaultsToAThirdOfTheShutdown() {
    ProxyConfig defaults = ProxyConfig.builder().build();
    assertThat(defaults.effectiveShutdownTimeoutMillis()).isEqualTo(5_000);
    assertThat(defaults.effectiveDrainTimeoutMillis()).isEqualTo(1_666);
    ProxyConfig longer = ProxyConfig.builder().shutdownTimeoutMillis(30_000).build();
    assertThat(longer.effectiveDrainTimeoutMillis()).isEqualTo(10_000);
  }

  @Test
  void configuredDrainTimeoutIsKept() {
    ProxyConfig config =
        ProxyConfig.builder()
            .requestLog(RequestLogConfig.builder().drainTimeoutMillis(5_000).build())
            .build();
    assertThat(config.effectiveDrainTimeoutMillis()).as("may take all of it").isEqualTo(5_000);
  }
}
