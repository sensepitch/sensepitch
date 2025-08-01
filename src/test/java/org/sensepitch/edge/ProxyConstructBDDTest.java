package org.sensepitch.edge;

import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Jens Wilke
 */
@ExtendWith(SerenityJUnit5Extension.class)
class ProxyConstructBDDTest {

  Steps steps = new Steps();

  @Test
  void testEmpty() {
    steps
      .given_the_configuration(ProxyConfig.builder().build())
      .then_expect_exception_with_message("Missing listen");
  }

  @Test
  void testWithEmptyListen() {
    ProxyConfig config = ProxyConfig.builder()
      .listen(ListenConfig.builder().build())
      .metrics(MetricsConfig.builder()
        .enable(false)
        .build())
      .sites(Map.of(
        "somesite", SiteConfig.builder()
            .protection(ProtectionConfig.builder()
              .enabled(false)
              .build())
            .upstream(UpstreamConfig.builder()
              .target("localhost")
              .build())
          .build()
      ))
      .build();
    steps
      .given_the_configuration(config)
      .then_expect_exception_with_message(
        "SSL setup missing");
  }

  @Test
  void testWithSSL() {
    ProxyConfig config = ProxyConfig.builder()
      .listen(ListenConfig.builder()
        .ssl(SslConfig.builder()
          .keyPath("classpath:ssl/test.key")
          .certPath("classpath:ssl/test.crt")
          .build())
        .build())
      .metrics(MetricsConfig.builder()
        .enable(false)
        .build())
      .build();
    steps
      .given_the_configuration(config)
      .then_expect_exception_with_message(
        "sites missing");
  }

  @Test
  void testWithOneSite() {
    ProxyConfig config = ProxyConfig.builder()
      .listen(ListenConfig.builder()
        .ssl(SslConfig.builder()
          .keyPath("classpath:ssl/test.key")
          .certPath("classpath:ssl/test.crt")
          .build())
        .build())
      .sites(Map.of(
        "example.com", SiteConfig.builder()
          .build()
      ))
      .metrics(MetricsConfig.builder()
        .enable(false)
        .build())
      .build();
    steps
      .given_the_configuration(config)
      .then_expect_exception_with_message(
        "upstream missing");
  }

  @Test
  void testWithOneSiteWithResponse() {
    ProxyConfig config = ProxyConfig.builder()
      .listen(ListenConfig.builder()
        .ssl(SslConfig.builder()
          .keyPath("classpath:ssl/test.key")
          .certPath("classpath:ssl/test.crt")
          .build())
        .build())
      .sites(Map.of(
        "example.com", SiteConfig.builder()
          .responseText("demo")
          .build()
      ))
      .metrics(MetricsConfig.builder()
        .enable(false)
        .build())
      .build();
    steps
      .given_the_configuration(config)
      .then_expect_exception_with_message(
        "protection scheme");
  }

  @Test
  void testWithOneSiteWithResponseDisabledProtection() {
    ProxyConfig config = ProxyConfig.builder()
      .listen(ListenConfig.builder()
        .ssl(SslConfig.builder()
          .keyPath("classpath:ssl/test.key")
          .certPath("classpath:ssl/test.crt")
          .build())
        .build())
      .sites(Map.of(
        "example.com", SiteConfig.builder()
          .responseText("demo")
          .protection(ProtectionConfig.builder()
            .enabled(false)
            .build())
          .build()
      ))
      .metrics(MetricsConfig.builder()
        .enable(false)
        .build())
      .build();
    steps
      .given_the_configuration(config)
      .then_expect_initialized_without_exception();
  }

  static class Steps extends ExtendableSteps<Steps> { }

  @SuppressWarnings({"unchecked", "UnusedReturnValue"})
  static class ExtendableSteps<T extends ExtendableSteps<?>> {

    Proxy proxy;
    Throwable constructionFailure;

    @Step(
      "Given a common configuration:")
    T given_the_configuration(ProxyConfig proxyConfig) {
      try {
        proxy = new Proxy(proxyConfig);
      } catch (Throwable throwable) {
        constructionFailure = throwable;
      }
      return (T) this;
    }

    @Step()
    T then_expect_exception_with_message(String expectedMessage) {
      String message = constructionFailure.getMessage();
      if (message == null) { message = ""; }
      if (constructionFailure instanceof IllegalArgumentException && message.contains(expectedMessage)) {
        return (T) this;
      }
      if (constructionFailure == null) {
        throw new AssertionError("Exception expected: " + IllegalArgumentException.class);
      }
      // chain
      throw new AssertionError("Unexpected exception: " + constructionFailure, constructionFailure);
    }

    @Step
    T then_expect_initialized_without_exception() {
      assertThat(constructionFailure).isNull();
      return (T) this;
    }

  }

}
