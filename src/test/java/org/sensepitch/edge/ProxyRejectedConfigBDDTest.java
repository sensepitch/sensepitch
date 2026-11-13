package org.sensepitch.edge;

import static org.sensepitch.edge.ProxyConstructBDDTest.LOCAL;
import static org.sensepitch.edge.ProxyConstructBDDTest.NO_METRICS;
import static org.sensepitch.edge.ProxyConstructBDDTest.SSL_LISTEN;
import static org.sensepitch.edge.ProxyConstructBDDTest.path;
import static org.sensepitch.edge.ProxyConstructBDDTest.ref;
import static org.sensepitch.edge.ProxyConstructBDDTest.routingConfig;
import static org.sensepitch.edge.ProxyConstructBDDTest.siteConfig;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Configurations the [Proxy] rejects at construction, each with a part of the expected message.
/// The accepted configurations are in [ProxyConstructBDDTest].
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class ProxyRejectedConfigBDDTest {

  ProxyConstructBDDTest.Steps steps = new ProxyConstructBDDTest.Steps();

  static Arguments rejected(String name, ProxyConfig config, String message) {
    return Arguments.of(name, config, message);
  }

  static Stream<Arguments> rejectedConfigurations() {
    ProxyConfig upstreamSite = routingConfig(SiteConfig.builder().upstream(LOCAL).build());
    return Stream.of(
        rejected("empty", ProxyConfig.builder().build(), "Missing listen"),
        rejected(
            "empty listen",
            ProxyConfig.builder()
                .listen(ListenConfig.builder().build())
                .metrics(NO_METRICS)
                .sites(
                    Map.of(
                        "somesite",
                        SiteConfig.builder()
                            .protection(ProtectionConfig.builder().disable(true).build())
                            .upstream(UpstreamConfig.builder().target("localhost").build())
                            .build()))
                .build(),
            "SSL setup missing"),
        rejected(
            "request log drain longer than the shutdown",
            upstreamSite.toBuilder()
                .shutdownTimeoutMillis(1_000)
                .requestLog(RequestLogConfig.builder().drainTimeoutMillis(2_000).build())
                .build(),
            "requestLog.drainTimeoutMillis 2000 exceeds shutdownTimeoutMillis 1000"),
        rejected(
            "negative request log drain timeout",
            upstreamSite.toBuilder()
                .requestLog(RequestLogConfig.builder().drainTimeoutMillis(-1).build())
                .build(),
            "must not be negative"),
        rejected(
            "negative shutdown timeout",
            upstreamSite.toBuilder().shutdownTimeoutMillis(-1).build(),
            "must not be negative"),
        rejected(
            "no sites",
            ProxyConfig.builder().listen(SSL_LISTEN).metrics(NO_METRICS).build(),
            "sites missing"),
        rejected(
            "site without upstream", siteConfig(SiteConfig.builder().build()), "upstream missing"),
        rejected(
            "response without protection setting",
            siteConfig(
                SiteConfig.builder()
                    .response(ResponseConfig.builder().text("demo").build())
                    .build()),
            "protection scheme"),
        rejected(
            "host wildcard",
            upstreamSite.toBuilder()
                .sites(Map.of("*.example.com", upstreamSite.sites().get("example.com")))
                .build(),
            "Host wildcards are not yet supported"),
        rejected(
            "site uri not a prefix",
            routingConfig(SiteConfig.builder().uri("/api").upstream(LOCAL).build()),
            "Site match uri needs to be prefix"),
        rejected(
            "path without upstream or response",
            routingConfig(
                SiteConfig.builder()
                    .upstream(LOCAL)
                    .paths(Map.of("/api/*", PathRouteConfig.builder().build()))
                    .build()),
            "Path requires upstream or response: /api/*"),
        rejected(
            "path with upstream and response",
            routingConfig(
                SiteConfig.builder()
                    .upstream(LOCAL)
                    .paths(
                        Map.of(
                            "/bo/*",
                            PathRouteConfig.builder()
                                .upstream(LOCAL)
                                .response(
                                    ResponseConfig.builder().status(404).text("Not Found").build())
                                .build()))
                    .build()),
            "Path requires upstream or response: /bo/*"),
        rejected(
            "override without cookie",
            routingConfig(
                SiteConfig.builder()
                    .upstream(LOCAL)
                    .overrides(List.of(RoutingOverrideConfig.builder().upstream(LOCAL).build()))
                    .build()),
            "Routing override requires whenCookie"),
        rejected(
            "override without upstream",
            routingConfig(
                SiteConfig.builder()
                    .upstream(LOCAL)
                    .overrides(
                        List.of(
                            RoutingOverrideConfig.builder()
                                .whenCookie("beta")
                                .paths(Map.of("/api/*", path(LOCAL)))
                                .build()))
                    .build()),
            "Routing override requires upstream: beta"),
        rejected(
            "unknown upstream ref",
            routingConfig(SiteConfig.builder().upstream(ref("missing")).build()),
            "unknown upstream ref: missing"),
        rejected(
            "upstream ref combined with target",
            routingConfig(
                SiteConfig.builder()
                    .upstream(UpstreamConfig.builder().ref("api").target("localhost").build())
                    .build()),
            "Upstream ref must not be combined"),
        rejected(
            "upstream without target or ref",
            routingConfig(SiteConfig.builder().upstream(UpstreamConfig.builder().build()).build()),
            "Upstream requires target or ref"),
        rejected(
            "named upstream with ref",
            upstreamSite.toBuilder().upstreams(Map.of("api", LOCAL, "alias", ref("api"))).build(),
            "Named upstream must not reference another upstream: alias"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedConfigurations")
  void configurationIsRejected(String name, ProxyConfig config, String expectedMessage) {
    steps.given_the_configuration(config).then_expect_exception_with_message(expectedMessage);
  }
}
