package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// The configuration records are complete: the defaults, the derived ones included, are values of
/// the built record, so the dumped configuration, see [Proxy#dumpConfig(ProxyConfig)], loaded
/// again gives an equal record. A generic test of the loaders ([Main#parseConfig(String)],
/// [EnvInjector]) and the builders, with a fixed [ProxyEnvironment], so the derived values are
/// the same on every machine.
@ExtendWith(SerenityJUnit5Extension.class)
class ConfigRoundTripBDDTest {

  static final ProxyEnvironment THREE_EVENT_LOOPS = new ProxyEnvironment(3);

  static final String OTLP_DESTINATION =
      """
      requestLog:
        destinations:
          - type: otlp
            name: vl
            otlp:
              endpoint: http://victorialogs:9428/insert/opentelemetry/v1/logs
              headers:
                VL-Stream-Fields: service.name
              resourceAttributes:
                service.name: edge
      """;

  @Test
  void minimalConfigurationShowsAllDefaults() {
    new Steps()
        .givenYaml("listen:\n  httpsPort: 8443\n")
        .whenLoaded()
        .thenTheDumpContains("shutdownTimeoutMillis: 5000", "drainTimeoutMillis: 1666")
        .thenTheDumpLoadsAsAnEqualRecord();
  }

  @Test
  void derivedDefaultsFollowTheEnvironment() {
    new Steps()
        .givenYaml(OTLP_DESTINATION)
        .whenLoaded()
        .thenTheOtlpConfigIs(
            OtlpConfig.builder(THREE_EVENT_LOOPS)
                .endpoint("http://victorialogs:9428/insert/opentelemetry/v1/logs")
                .headers(Map.of("VL-Stream-Fields", "service.name"))
                .resourceAttributes(Map.of("service.name", "edge"))
                .build())
        .thenTheOtlpLimitsAre(3, 2L * 3 * OtlpConfig.DEFAULT_BATCH_BYTES)
        .thenTheDumpContains(
            "maxConcurrentRequests: 3",
            "maxBufferedBytes: " + 2L * 3 * OtlpConfig.DEFAULT_BATCH_BYTES,
            "compression: gzip",
            "timeoutMillis: " + OtlpConfig.DEFAULT_TIMEOUT_MILLIS)
        .thenTheDumpLoadsAsAnEqualRecord();
  }

  @Test
  void derivedDefaultsFollowTheConfiguredValues() {
    new Steps()
        .givenYaml(
            OTLP_DESTINATION
                + "        batchBytes: 131072\n"
                + "shutdownTimeoutMillis: 9000\n"
                + "shutdownAcceptGraceMillis: 1000\n")
        .whenLoaded()
        .thenTheOtlpLimitsAre(3, 2L * 3 * 131072)
        .thenTheDrainTimeoutIs(3000)
        .thenTheDumpLoadsAsAnEqualRecord();
  }

  @Test
  void configuredValuesAreKept() {
    new Steps()
        .givenYaml(
            OTLP_DESTINATION
                + "        maxConcurrentRequests: 7\n"
                + "        maxBufferedBytes: 99999999\n"
                + "        compressionLevel: 6\n"
                + "  drainTimeoutMillis: 1000\n")
        .whenLoaded()
        .thenTheOtlpLimitsAre(7, 99_999_999)
        .thenTheDrainTimeoutIs(1000)
        .thenTheDumpContains("compressionLevel: 6")
        .thenTheDumpLoadsAsAnEqualRecord();
  }

  /// The environment variables give the same record as the YAML file
  @Test
  void environmentVariablesUseTheSameDefaults() throws Exception {
    new Steps()
        .givenYaml(OTLP_DESTINATION)
        .whenLoaded()
        .thenEnvironmentVariablesGiveTheSameRecord(
            Map.of(
                "XX_REQUEST_LOG_DESTINATIONS_0_TYPE", "otlp",
                "XX_REQUEST_LOG_DESTINATIONS_0_NAME", "vl",
                "XX_REQUEST_LOG_DESTINATIONS_0_OTLP_ENDPOINT",
                    "http://victorialogs:9428/insert/opentelemetry/v1/logs",
                "XX_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_KEY", "VL-Stream-Fields",
                "XX_REQUEST_LOG_DESTINATIONS_0_OTLP_HEADERS_0_VALUE", "service.name",
                "XX_REQUEST_LOG_DESTINATIONS_0_OTLP_RESOURCE_ATTRIBUTES_0_KEY", "service.name",
                "XX_REQUEST_LOG_DESTINATIONS_0_OTLP_RESOURCE_ATTRIBUTES_0_VALUE", "edge"));
  }

  static class Steps {
    String yaml;
    ProxyConfig config;

    @Step("Given the YAML configuration {0}")
    Steps givenYaml(String value) {
      yaml = value;
      return this;
    }

    @Step("Loaded with an environment of three event loops")
    Steps whenLoaded() {
      config = Main.parseConfig(yaml, THREE_EVENT_LOOPS);
      return this;
    }

    @Step("The OTLP destination has the configuration {0}")
    Steps thenTheOtlpConfigIs(OtlpConfig expected) {
      assertThat(otlp()).isEqualTo(expected);
      return this;
    }

    @Step("The OTLP destination sends {0} requests at once and buffers up to {1} bytes")
    Steps thenTheOtlpLimitsAre(int maxConcurrentRequests, long maxBufferedBytes) {
      assertThat(otlp().maxConcurrentRequests()).isEqualTo(maxConcurrentRequests);
      assertThat(otlp().maxBufferedBytes()).isEqualTo(maxBufferedBytes);
      return this;
    }

    @Step("The request log drain timeout is {0} ms")
    Steps thenTheDrainTimeoutIs(long millis) {
      assertThat(config.requestLog().drainTimeoutMillis()).isEqualTo(millis);
      config.checkShutdownBudget();
      return this;
    }

    @Step("The dumped configuration contains {0}")
    Steps thenTheDumpContains(String... lines) {
      assertThat(Proxy.toYaml(config)).contains(lines);
      return this;
    }

    @Step("The dumped configuration, loaded again, gives an equal record")
    Steps thenTheDumpLoadsAsAnEqualRecord() {
      String dump = Proxy.toYaml(config);
      ProxyConfig reloaded = Main.parseConfig(dump, THREE_EVENT_LOOPS);
      assertThat(reloaded).isEqualTo(config);
      assertThat(Proxy.toYaml(reloaded)).isEqualTo(dump);
      return this;
    }

    @Step("The environment variables {0} give the same record")
    Steps thenEnvironmentVariablesGiveTheSameRecord(Map<String, String> env) throws Exception {
      assertThat(EnvInjector.injectFromEnv("XX_", env, THREE_EVENT_LOOPS, ProxyConfig.builder()))
          .isEqualTo(config);
      return this;
    }

    OtlpConfig otlp() {
      return config.requestLog().destinations().getFirst().otlp();
    }
  }
}
