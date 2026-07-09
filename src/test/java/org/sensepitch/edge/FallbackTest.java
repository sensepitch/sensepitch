package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpResponseStatus;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

/**
 * @author Raid Thabet
 */
@ExtendWith(SerenityJUnit5Extension.class)
public class FallbackTest {

    static final ProxyConfig CONFIG =
            ProxyConfig.builder()
                    .listen(
                            ListenConfig.builder()
                                    .ssl(
                                            SslConfig.builder()
                                                    .keyPath("classpath:ssl/test.key")
                                                    .certPath("classpath:ssl/test.crt")
                                                    .build())
                                    .build())
                    // global fallback -> inherited unless a site overrides a field
                    .fallback(
                            FallbackConfig.builder()
                                    .unavailableText("global-unavailable")
                                    .errorText("global-error")
                                    .build())
                    .sites(
                            Map.ofEntries(
                                    Map.entry(
                                            "down.com",
                                            SiteConfig.builder()
                                                    .response(ResponseConfig.builder().status(503).text("ignored").build())
                                                    .protection(ProtectionConfig.builder().disable(true).build())
                                                    .build()),
                                    Map.entry(
                                            "boom.com",
                                            SiteConfig.builder()
                                                    .response(ResponseConfig.builder().status(500).text("ignored").build())
                                                    .protection(ProtectionConfig.builder().disable(true).build())
                                                    .build()),
                                    Map.entry(
                                            "site3.de",
                                            SiteConfig.builder()
                                                    .response(ResponseConfig.builder().status(503).text("ignored").build())
                                                    .protection(ProtectionConfig.builder().disable(true).build())
                                                    .fallback(FallbackConfig.builder().unavailableText("site3-unavailable").build())
                                                    .build()),
                                    Map.entry(
                                            "ok.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(200).text("real-body").build())
                                            .protection(ProtectionConfig.builder().disable(true).build()).build()),
                                    Map.entry(
                                            "notfound.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(404).text("real-404").build())
                                            .protection(ProtectionConfig.builder().disable(true).build()).build()),
                                    Map.entry(
                                            "unavailable-page.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(503).text("ignored").build())
                                            .protection(ProtectionConfig.builder().disable(true).build())
                                            .fallback(FallbackConfig.builder().unavailablePage("fallback/unavailable_page.html").build())
                                            .build()),
                                    Map.entry(
                                            "error-page.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(500).text("ignored").build())
                                            .protection(ProtectionConfig.builder().disable(true).build())
                                            .fallback(FallbackConfig.builder().errorPage("fallback/error_page.html").build())
                                            .build()),
                                    Map.entry(
                                            "unavailable-text.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(503).text("ignored").build())
                                            .protection(ProtectionConfig.builder().disable(true).build())
                                            .fallback(FallbackConfig.builder().unavailableText("service is unavailable").build())
                                            .build()),
                                    Map.entry(
                                            "error-text.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(500).text("ignored").build())
                                            .protection(ProtectionConfig.builder().disable(true).build())
                                            .fallback(FallbackConfig.builder().errorText("internal server error").build())
                                            .build()),
                                    Map.entry(
                                            "unavailable-page-over-text.com",
                                            SiteConfig.builder()
                                            .response(ResponseConfig.builder().status(503).text("ignored").build())
                                            .protection(ProtectionConfig.builder().disable(true).build())
                                            .fallback(FallbackConfig.builder()
                                                    .unavailablePage("fallback/unavailable_page.html")
                                                    .unavailableText("service is unavailable")
                                                    .build())
                                            .build()),
                                    Map.entry(
                                            "error-page-over-text.com",
                                            SiteConfig.builder()
                                                    .response(ResponseConfig.builder().status(500).text("ignored").build())
                                                    .protection(ProtectionConfig.builder().disable(true).build())
                                                    .fallback(FallbackConfig.builder()
                                                            .errorPage("fallback/error_page.html")
                                                            .errorText("internal server error")
                                                            .build())
                                                    .build())
                            ))
                    .metrics(MetricsConfig.builder().enable(false).build())
                    .build();

    CompleteTest.Steps steps = new CompleteTest.Steps().given_initialized_proxy_with(CONFIG);

    @Test
    public void testGlobalFalback() {
        steps
                .when_requesting("down.com", "/")
                .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
                .then_expect_content("global-unavailable")
                .then_channel_open();

        steps
                .when_requesting("boom.com", "/")
                .then_the_response_status_is(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                .then_expect_content("global-error")
                .then_channel_open();
    }

    @Test
    public void testSiteFallback() {
        steps
                .when_requesting("site3.de", "/")
                .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
                .then_expect_content("site3-unavailable")
                .then_channel_open();
    }

    @Test
    public void testOkPassesThrough() {
        steps
                .when_requesting("ok.com", "/")
                .then_the_response_status_is(HttpResponseStatus.OK)
                .then_expect_content("real-body");
    }

    @Test
    public void testNon5xxPassesThrough() {
        steps
                .when_requesting("notfound.com", "/")
                .then_the_response_status_is(HttpResponseStatus.NOT_FOUND)
                .then_expect_content("real-404");
    }

    @Test
    public void testSiteFallbackUnavailablePageFromClasspath() {
        steps
                .when_requesting("unavailable-page.com", "/")
                .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
                .then_expect_content("<html>down</html>");
    }

    @Test
    public void testSiteFallbackErrorPageFromClasspath() {
        steps
                .when_requesting("error-page.com", "/")
                .then_the_response_status_is(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                .then_expect_content("<html>error</html>");
    }

    @Test
    public void testSiteFallbackUnavailableText() {
        steps
                .when_requesting("unavailable-text.com", "/")
                .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
                .then_expect_content("service is unavailable");
    }

    @Test
    public void testSiteFallbackErrorText() {
        steps
                .when_requesting("error-text.com", "/")
                .then_the_response_status_is(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                .then_expect_content("internal server error");
    }

    @Test
    public void testSiteFallbackUnavailablePageOverText() {
        steps
                .when_requesting("unavailable-page-over-text.com", "/")
                .then_the_response_status_is(HttpResponseStatus.SERVICE_UNAVAILABLE)
                .then_expect_content("<html>down</html>");
    }

    @Test
    public void testSiteFallbackErrorPageOverText() {
        steps
                .when_requesting("error-page-over-text.com", "/")
                .then_the_response_status_is(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                .then_expect_content("<html>error</html>");
    }

    @AfterEach
    void finish() {
        steps.finish_and_check_for_leaks();
    }
}
