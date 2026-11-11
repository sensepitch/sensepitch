package org.sensepitch.edge;

import static org.sensepitch.edge.ConfigReloadTest.CONFIG;
import static org.sensepitch.edge.ConfigReloadTest.sites;

import io.netty.handler.codec.http.HttpResponseStatus;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// Selecting a site by host and [SiteConfig#uri()] prefix, see [SiteSelector#getSuppliers]
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class SiteUriMatchTest {

  CompleteTest.Steps steps;

  @AfterEach
  void finish() {
    steps.finish_and_check_for_leaks();
  }

  static SiteConfig site(String uri, String text) {
    return SiteConfig.builder()
        .host("a.com")
        .uri(uri)
        .response(ResponseConfig.builder().text(text).build())
        .build();
  }

  void given_sites(Object... keyAndSite) {
    steps =
        new CompleteTest.Steps()
            .given_initialized_proxy_with(CONFIG.toBuilder().sites(sites(keyAndSite)).build());
  }

  @Test
  void siblingPrefixFallsBackToShorterPrefix() {
    given_sites("a", site("/a*", "A"), "ab", site("/ab*", "AB"));
    steps.when_requesting("a.com", "/ac").then_expect_content("A");
    steps.when_requesting("a.com", "/abc").then_expect_content("AB");
    steps.when_requesting("a.com", "/ab").then_expect_content("AB");
    steps.when_requesting("a.com", "/a").then_expect_content("A");
  }

  @Test
  void longestPrefixWinsIndependentOfOrder() {
    given_sites(
        "api-v2", site("/api/v2/*", "V2"), "root", site("/x*", "X"), "api", site("/api/*", "API"));
    steps.when_requesting("a.com", "/api/v2/items").then_expect_content("V2");
    steps.when_requesting("a.com", "/api/v3/items").then_expect_content("API");
    steps.when_requesting("a.com", "/api/vz").then_expect_content("API");
    steps.when_requesting("a.com", "/xyz").then_expect_content("X");
  }

  @Test
  void noMatchingPrefixIsNotFound() {
    given_sites("a", site("/a*", "A"), "ab", site("/ab*", "AB"));
    steps.when_requesting("a.com", "/b").then_the_response_status_is(HttpResponseStatus.NOT_FOUND);
    steps.when_requesting("a.com", "/").then_the_response_status_is(HttpResponseStatus.NOT_FOUND);
  }
}
