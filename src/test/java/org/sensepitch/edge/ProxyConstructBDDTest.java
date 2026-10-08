package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// @author Jens Wilke
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
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(ListenConfig.builder().build())
            .metrics(MetricsConfig.builder().enable(false).build())
            .sites(
                Map.of(
                    "somesite",
                    SiteConfig.builder()
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .upstream(UpstreamConfig.builder().target("localhost").build())
                        .build()))
            .build();
    steps.given_the_configuration(config).then_expect_exception_with_message("SSL setup missing");
  }

  @Test
  void testWithSSL() {
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .build())
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();
    steps.given_the_configuration(config).then_expect_exception_with_message("sites missing");
  }

  @Test
  void testWithOneSite() {
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .build())
            .sites(Map.of("example.com", SiteConfig.builder().build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();
    steps.given_the_configuration(config).then_expect_exception_with_message("upstream missing");
  }

  @Test
  void testWithOneSiteWithResponse() {
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .build())
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("demo").build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();
    steps.given_the_configuration(config).then_expect_exception_with_message("protection scheme");
  }

  @Test
  void testWithOneSiteWithResponseDisabledProtection() {
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .build())
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("demo").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();
    steps.given_the_configuration(config).then_expect_initialized_without_exception();
  }

  @Test
  void testResponseConfigMixingTextAndLocationFailsParsing() {
    assertThatThrownBy(
            () ->
                SiteConfig.builder()
                    .response(
                        ResponseConfig.builder()
                            .text("demo")
                            .location("https://elsewhere.example/")
                            .build())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response should be one of");
  }

  static final UpstreamConfig LOCAL = UpstreamConfig.builder().target("localhost:8080").build();

  static UpstreamConfig ref(String name) {
    return UpstreamConfig.builder().ref(name).build();
  }

  static PathRouteConfig path(UpstreamConfig upstream) {
    return PathRouteConfig.builder().upstream(upstream).build();
  }

  /// Configuration with named upstreams `api` and `beta` and a single site.
  static ProxyConfig routingConfig(SiteConfig site) {
    return ProxyConfig.builder()
        .listen(
            ListenConfig.builder()
                .ssl(
                    SslConfig.builder()
                        .keyPath("classpath:ssl/test.key")
                        .certPath("classpath:ssl/test.crt")
                        .build())
                .build())
        .upstreams(Map.of("api", LOCAL, "beta", LOCAL))
        .sites(
            Map.of(
                "example.com",
                site.toBuilder()
                    .protection(ProtectionConfig.builder().disable(true).build())
                    .build()))
        .metrics(MetricsConfig.builder().enable(false).build())
        .build();
  }

  @Test
  void routingWithPathsOverridesAndNamedUpstreams() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(ref("api"))
            .paths(Map.of("/api/*", path(ref("api")), "/api/v2/*", path(LOCAL)))
            .overrides(
                List.of(
                    RoutingOverrideConfig.builder()
                        .whenCookie("beta")
                        .upstream(ref("beta"))
                        .paths(Map.of("/api/*", path(ref("beta"))))
                        .build()))
            .build();
    steps.given_the_configuration(routingConfig(site)).then_expect_initialized_without_exception();
  }

  @Test
  void pathMustBePrefix() {
    SiteConfig site =
        SiteConfig.builder().upstream(LOCAL).paths(Map.of("/api", path(LOCAL))).build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Path needs to be a prefix ending with *: /api");
  }

  @Test
  void pathRequiresUpstreamOrResponse() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(LOCAL)
            .paths(Map.of("/api/*", PathRouteConfig.builder().build()))
            .build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Path requires upstream or response: /api/*");
  }

  @Test
  void pathWithUpstreamAndResponseFails() {
    PathRouteConfig both =
        PathRouteConfig.builder()
            .upstream(LOCAL)
            .response(ResponseConfig.builder().status(404).text("Not Found").build())
            .build();
    SiteConfig site = SiteConfig.builder().upstream(LOCAL).paths(Map.of("/bo/*", both)).build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Path requires upstream or response: /bo/*");
  }

  @Test
  void overrideRequiresCookie() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(LOCAL)
            .overrides(List.of(RoutingOverrideConfig.builder().upstream(LOCAL).build()))
            .build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Routing override requires whenCookie");
  }

  @Test
  void overrideRequiresUpstream() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(LOCAL)
            .overrides(
                List.of(
                    RoutingOverrideConfig.builder()
                        .whenCookie("beta")
                        .paths(Map.of("/api/*", path(LOCAL)))
                        .build()))
            .build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Routing override requires upstream: beta");
  }

  @Test
  void unknownUpstreamRef() {
    SiteConfig site = SiteConfig.builder().upstream(ref("missing")).build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("unknown upstream ref: missing");
  }

  @Test
  void upstreamRefNotCombinedWithTarget() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(UpstreamConfig.builder().ref("api").target("localhost").build())
            .build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Upstream ref must not be combined");
  }

  @Test
  void upstreamRequiresTargetOrRef() {
    SiteConfig site = SiteConfig.builder().upstream(UpstreamConfig.builder().build()).build();
    steps
        .given_the_configuration(routingConfig(site))
        .then_expect_exception_with_message("Upstream requires target or ref");
  }

  @Test
  void namedUpstreamMustNotUseRef() {
    ProxyConfig config =
        routingConfig(SiteConfig.builder().upstream(LOCAL).build()).toBuilder()
            .upstreams(Map.of("api", LOCAL, "alias", ref("api")))
            .build();
    steps
        .given_the_configuration(config)
        .then_expect_exception_with_message(
            "Named upstream must not reference another upstream: alias");
  }

  static class Steps extends ExtendableSteps<Steps> {}

  @SuppressWarnings({"unchecked", "UnusedReturnValue"})
  static class ExtendableSteps<T extends ExtendableSteps<?>> {

    Proxy proxy;
    Throwable constructionFailure;

    @Step("Given a common configuration:")
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
      if (message == null) {
        message = "";
      }
      if (constructionFailure instanceof IllegalArgumentException
          && message.contains(expectedMessage)) {
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
