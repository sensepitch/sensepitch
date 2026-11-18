package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Retry delays of a request log destination: defaults, effective values and rejected values, from
/// the configuration record to the [Backoff] of the sender, without requests or timers. The retry
/// itself is covered by the sender and breaker tests.
@ExtendWith(SerenityJUnit5Extension.class)
class OtlpRetryConfigBDDTest {

  @Test
  void omittedDelaysKeepTheDefaults() {
    new Steps()
        .givenConfig(OtlpConfig.builder().endpoint("http://127.0.0.1:1/v1/logs").build())
        .thenEffectiveDelaysAre(1_000, 30_000)
        .thenProductionBackoffIs(1_000, 30_000);
  }

  @ParameterizedTest
  @CsvSource({
    "0, 0, 1000, 30000",
    "250, 0, 250, 30000",
    "0, 60000, 1000, 60000",
    "0, 1000, 1000, 1000",
    "30000, 0, 30000, 30000",
    "1, 1, 1, 1",
    "40000, 60000, 40000, 60000",
    "2147483647, 2147483647, 2147483647, 2147483647"
  })
  void configuredDelaysReachTheSender(
      int initial, int max, int effectiveInitial, int effectiveMax) {
    new Steps()
        .givenDelays(initial, max)
        .thenEffectiveDelaysAre(effectiveInitial, effectiveMax)
        .thenProductionBackoffIs(effectiveInitial, effectiveMax);
  }

  @ParameterizedTest
  @CsvSource({
    "-1, 0, initialRetryDelayMillis must not be negative",
    "0, -1, maxRetryDelayMillis must not be negative",
    "2000, 1000, effective maxRetryDelayMillis must be at least effective initialRetryDelayMillis",
    "0, 999, effective maxRetryDelayMillis must be at least effective initialRetryDelayMillis",
    "30001, 0, effective maxRetryDelayMillis must be at least effective initialRetryDelayMillis"
  })
  void invalidDelaysAreRejected(int initial, int max, String reason) {
    new Steps().givenDelays(initial, max).thenDestinationIsRejected(reason);
  }

  static class Steps {
    OtlpConfig config;

    @Step("Configure initial delay {0} ms and maximum delay {1} ms")
    Steps givenDelays(int initial, int max) {
      return givenConfig(
          OtlpConfig.builder()
              .endpoint("http://127.0.0.1:1/v1/logs")
              .initialRetryDelayMillis(initial)
              .maxRetryDelayMillis(max)
              .build());
    }

    @Step("Use the destination configuration")
    Steps givenConfig(OtlpConfig value) {
      config = value;
      return this;
    }

    @Step("Effective delays are {0} ms and {1} ms")
    Steps thenEffectiveDelaysAre(int initial, int max) {
      assertThat(config.effectiveInitialRetryDelayMillis()).isEqualTo(initial);
      assertThat(config.effectiveMaxRetryDelayMillis()).isEqualTo(max);
      return this;
    }

    @Step("The sender backs off from {0} ms up to {1} ms with jitter")
    Steps thenProductionBackoffIs(int initial, int max) {
      assertThat(OtlpLogDestination.settings("configured", config).backoff())
          .isEqualTo(new Backoff(initial, max));
      return this;
    }

    @Step("Destination validation rejects the configuration: {0}")
    Steps thenDestinationIsRejected(String reason) {
      assertThatThrownBy(() -> new OtlpLogDestination("invalid", config))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Request log destination invalid: " + reason);
      return this;
    }
  }
}
