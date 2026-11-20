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
    assertThat(defaults.shutdownTimeoutMillis()).isEqualTo(5_000);
    assertThat(defaults.requestLog().drainTimeoutMillis()).isEqualTo(1_666);
    ProxyConfig longer = ProxyConfig.builder().shutdownTimeoutMillis(30_000).build();
    assertThat(longer.requestLog().drainTimeoutMillis()).isEqualTo(10_000);
    ProxyConfig tiny = ProxyConfig.builder().shutdownTimeoutMillis(2).build();
    assertThat(tiny.requestLog().drainTimeoutMillis()).as("never zero").isEqualTo(1);
  }

  /// The derived drain timeout is set in the configured request log, which keeps its other values
  @Test
  void drainTimeoutIsDerivedInTheConfiguredRequestLog() {
    ProxyConfig config =
        ProxyConfig.builder()
            .shutdownTimeoutMillis(9_000)
            .requestLog(RequestLogConfig.builder().plainText(true).build())
            .build();
    assertThat(config.requestLog())
        .isEqualTo(RequestLogConfig.builder().plainText(true).drainTimeoutMillis(3_000L).build());
  }

  @Test
  void configuredDrainTimeoutIsKept() {
    ProxyConfig config =
        ProxyConfig.builder()
            .requestLog(RequestLogConfig.builder().drainTimeoutMillis(5_000L).build())
            .build();
    assertThat(config.requestLog().drainTimeoutMillis()).as("may take all of it").isEqualTo(5_000);
    config.checkShutdownBudget();
  }

  /// A complete record keeps its values in `toBuilder()`, the derived drain timeout included
  @Test
  void toBuilderKeepsTheDerivedDrainTimeout() {
    ProxyConfig config = ProxyConfig.builder().build();
    ProxyConfig longer = config.toBuilder().shutdownTimeoutMillis(30_000).build();
    assertThat(longer.requestLog().drainTimeoutMillis()).isEqualTo(1_666);
    assertThat(config.toBuilder().build()).isEqualTo(config);
  }
}
