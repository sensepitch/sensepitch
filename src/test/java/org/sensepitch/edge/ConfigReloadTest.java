package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Reload of the configuration at runtime, see [Proxy#reload(ProxyConfig)]
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class ConfigReloadTest {

  static final SslConfig TEST_SSL =
      SslConfig.builder()
          .keyPath("classpath:ssl/test.key")
          .certPath("classpath:ssl/test.crt")
          .build();

  static final ProxyConfig CONFIG =
      ProxyConfig.builder()
          .listen(ListenConfig.builder().ssl(TEST_SSL).build())
          .unservicedHost(
              UnservicedHostConfig.builder().defaultLocation("https://sensepitch.io").build())
          .protection(ProtectionConfig.builder().disable(true).build())
          .sites(sites("a.com", response("A")))
          .metrics(MetricsConfig.builder().enable(false).build())
          .build();

  @RegisterExtension LogCapture logs = new LogCapture();

  CompleteTest.Steps steps = new CompleteTest.Steps().given_initialized_proxy_with(CONFIG);

  @AfterEach
  void finish() {
    steps.finish_and_check_for_leaks();
  }

  static SiteConfig response(String text) {
    return SiteConfig.builder().response(ResponseConfig.builder().text(text).build()).build();
  }

  static SiteConfig upstream(UpstreamConfig upstream) {
    return SiteConfig.builder().upstream(upstream).build();
  }

  static UpstreamConfig target(String target) {
    return UpstreamConfig.builder().target(target).build();
  }

  /// Sites in the given order, so the YAML dump is stable
  static Map<String, SiteConfig> sites(Object... keyAndSite) {
    Map<String, SiteConfig> map = new LinkedHashMap<>();
    for (int i = 0; i < keyAndSite.length; i += 2) {
      map.put((String) keyAndSite[i], (SiteConfig) keyAndSite[i + 1]);
    }
    return map;
  }

  boolean reload(ProxyConfig cfg) {
    return steps.proxy.reload(cfg).applied();
  }

  @Test
  void reloadWithoutListenIsRejected() {
    ReloadResult result = steps.proxy.reload(CONFIG.toBuilder().listen(null).build());
    assertThat(result.applied()).isFalse();
    assertThat(result.message()).contains("Missing listen configuration");
    steps.when_requesting("a.com", "/").then_expect_content("A");
  }

  @Test
  void keepAliveConnectionUsesNewConfigOnNextRequest() {
    steps.when_requesting("a.com", "/").then_expect_content("A");
    var channel = steps.ingressChannel;
    assertThat(reload(CONFIG.toBuilder().sites(sites("a.com", response("B"))).build())).isTrue();
    steps.when_requesting("a.com", "/").then_expect_content("B");
    assertThat(steps.ingressChannel).isSameAs(channel);
  }

  @Test
  void hostsAddedAndRemoved() {
    // unknown host
    steps.when_requesting("b.com", "/").then_the_response_status_is(HttpResponseStatus.BAD_REQUEST);
    reload(
        CONFIG.toBuilder()
            .listen(CONFIG.listen().toBuilder().hosts(List.of("a.com")).build())
            .sites(sites("b.com", response("B")))
            .build());
    steps.when_requesting("b.com", "/").then_expect_content("B");
    // still known via listen.hosts, but no longer serviced
    steps
        .when_requesting("a.com", "/")
        .then_the_response_status_is(HttpResponseStatus.TEMPORARY_REDIRECT)
        .then_the_response_location_header_is("https://sensepitch.io");
  }

  @Test
  void invalidConfigKeepsActiveConfig() {
    ProxyConfig unknownRef =
        CONFIG.toBuilder()
            .sites(sites("a.com", upstream(UpstreamConfig.builder().ref("missing").build())))
            .build();
    assertThat(steps.proxy.reload(unknownRef))
        .isEqualTo(ReloadResult.rejected("Configuration rejected: unknown upstream ref: missing"));
    ProxyConfig missingCert =
        CONFIG.toBuilder()
            .listen(
                CONFIG.listen().toBuilder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("/missing.key")
                            .certPath("/missing.crt")
                            .build())
                    .build())
            .build();
    assertThat(reload(missingCert)).isFalse();
    assertThat(logs.errors())
        .extracting(e -> e.getMessage().getFormattedMessage())
        .anyMatch(m -> m.contains("unknown upstream ref: missing"))
        .allMatch(m -> m.startsWith("Configuration rejected"))
        .hasSize(2);
    steps.when_requesting("a.com", "/").then_expect_content("A");
  }

  @Test
  void unchangedUpstreamsAreKept() {
    ProxyConfig cfg =
        CONFIG.toBuilder()
            .upstreams(Map.of("shared", target("shared:80")))
            .upstream(target("default:80"))
            .sites(
                sites(
                    "a.com", upstream(target("same:80")),
                    "b.com", upstream(target("same:80")),
                    "c.com", upstream(UpstreamConfig.builder().ref("shared").build())))
            .build();
    assertThat(reload(cfg)).isTrue();
    EdgeState first = steps.proxy.state();
    Upstream a = first.upstreams().lookup("sites.a.com.upstream");
    Upstream b = first.upstreams().lookup("sites.b.com.upstream");
    Upstream shared = first.upstreams().lookup("upstreams.shared");
    Upstream defaultUpstream = first.upstreams().lookup("upstream");
    assertThat(a).isNotNull().isNotSameAs(b);
    assertThat(shared).isNotNull();
    assertThat(defaultUpstream).isNotNull();
    assertThat(first.upstreams().lookup("sites.c.com.upstream")).isNull();

    ProxyConfig changed =
        cfg.toBuilder()
            .sites(
                sites(
                    "a.com", upstream(target("same:80")),
                    "b.com", upstream(target("other:80")),
                    "c.com", upstream(UpstreamConfig.builder().ref("shared").build())))
            .build();
    assertThat(reload(changed)).isTrue();
    EdgeState second = steps.proxy.state();
    assertThat(second.upstreams().lookup("sites.a.com.upstream")).isSameAs(a);
    assertThat(second.upstreams().lookup("sites.b.com.upstream")).isNotSameAs(b);
    assertThat(second.upstreams().lookup("upstreams.shared")).isSameAs(shared);
    assertThat(second.upstreams().lookup("upstream")).isSameAs(defaultUpstream);
  }

  /// The config id identifies the configuration: equal for an unchanged one, different after a
  /// change
  @Test
  void configIdChangesWithTheConfiguration() {
    String first = steps.proxy.state().configId();
    assertThat(first).matches("[0-9a-f]{12}");
    reload(CONFIG);
    assertThat(steps.proxy.state().configId()).as("unchanged").isEqualTo(first);
    reload(CONFIG.toBuilder().sites(sites("a.com", response("changed"))).build());
    assertThat(steps.proxy.state().configId()).isNotEqualTo(first);
    // computed from the configuration with the map keys injected, as on every node
    assertThat(EdgeState.configId(steps.proxy.state().config()))
        .as("same id on another node")
        .isEqualTo(steps.proxy.state().configId());
  }

  @Test
  void ipLookupOnlyRebuiltWhenChanged() {
    IpTraitsLookup none = steps.proxy.state().ipTraitsLookup();
    ProxyConfig withLookup = CONFIG.toBuilder().ipLookup(IpLookupConfig.builder().build()).build();
    reload(withLookup);
    IpTraitsLookup lookup = steps.proxy.state().ipTraitsLookup();
    assertThat(lookup).isNotSameAs(none);
    reload(withLookup.toBuilder().sites(sites("a.com", response("B"))).build());
    assertThat(steps.proxy.state().ipTraitsLookup()).isSameAs(lookup);
  }

  @Test
  void restartOnlyChangeIsWarnedAndIgnored() {
    ProxyConfig otherPort =
        CONFIG.toBuilder().listen(CONFIG.listen().toBuilder().httpsPort(4711).build()).build();
    assertThat(reload(otherPort)).isTrue();
    assertThat(logs.messages(Proxy.class))
        .contains("Configuration change of 'listen.httpsPort' needs a restart, ignored");
  }

  @Test
  void changesAreLoggedAsDiff() {
    reload(CONFIG.toBuilder().sites(sites("a.com", response("B"))).build());
    assertThat(logs.messages(Proxy.class))
        .anyMatch(
            m ->
                m.startsWith("Configuration reloaded, changes:")
                    && m.contains("-       text: A")
                    && m.contains("+       text: B"));
    logs.clear();
    reload(CONFIG.toBuilder().sites(sites("a.com", response("B"))).build());
    assertThat(logs.messages(Proxy.class)).contains("Configuration reloaded, no changes");
  }

  @Test
  void sniCertificatesReloaded() {
    var defaultContext = steps.proxy.state().sniMapping().map("other.com");
    ProxyConfig withSni =
        CONFIG.toBuilder()
            .listen(
                CONFIG.listen().toBuilder()
                    .snis(List.of(SniConfig.builder().host("other.com").ssl(TEST_SSL).build()))
                    .build())
            .build();
    assertThat(reload(withSni)).isTrue();
    assertThat(steps.proxy.state().sniMapping().map("other.com"))
        .isNotNull()
        .isNotSameAs(defaultContext);
    assertThat(logs.messages(Proxy.class))
        .anyMatch(
            m ->
                m.startsWith("Certificates reloaded, changes:")
                    && m.contains("added other.com: subject=")
                    && m.contains("notAfter=")
                    && !m.contains("default:"));
    logs.clear();
    reload(withSni);
    assertThat(logs.messages(Proxy.class)).contains("Certificates reloaded, no changes");
  }

  @Test
  void certificatesLoggedAtStartup() {
    logs.clear();
    new Proxy(CONFIG, new RequestLogCapture());
    assertThat(logs.messages(Proxy.class))
        .anyMatch(
            m ->
                m.startsWith("Certificates loaded:")
                    && m.contains("default: subject=")
                    && m.contains("file=classpath:ssl/test.crt"));
  }
}
