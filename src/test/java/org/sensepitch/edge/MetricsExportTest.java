package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import java.util.HashMap;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// The metrics a [Proxy] registers, read from [Proxy#metricsRegistry()]. The HTTP export is
/// disabled, see [MetricsServerTest] for that.
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class MetricsExportTest {

  CompleteTest.Steps steps = new CompleteTest.Steps().given_initialized_proxy_with(COMMON_CONFIG);

  @AfterEach
  void finish() {
    steps.finish_and_check_for_leaks();
  }

  MetricsAndMemoryTest.Snapshots scrape() {
    return new MetricsAndMemoryTest.Snapshots(steps.proxy.metricsRegistry().scrape());
  }

  @Test
  void requestCountersAndDuration() {
    steps.when_requesting("example.com", "/").then_the_response_status_is(HttpResponseStatus.OK);
    steps.when_requesting("example.com", "/x").then_the_response_status_is(HttpResponseStatus.OK);
    steps
        .when_requesting("withprotection.com", "/")
        .then_the_response_status_is(HttpResponseStatus.FORBIDDEN);
    var snapshots = scrape();
    assertThat(snapshots.counter("sensepitch_ingress_requests_started").getValue()).isEqualTo(3);
    assertThat(snapshots.counter("sensepitch_ingress_requests_completed").getValue()).isEqualTo(3);
    assertThat(histogramCount(snapshots, "example.com", "GET", "200")).isEqualTo(2);
    assertThat(histogramCount(snapshots, "withprotection.com", "GET", "403")).isEqualTo(1);
  }

  /// The counters live in the proxy, not in the per-site protection rebuilt by a reload.
  @Test
  void deflectorCountersSurviveReload() {
    steps.when_requesting("withprotection.com", "/");
    var sites = new HashMap<>(COMMON_CONFIG.sites());
    sites.put(
        "example.com",
        sites.get("example.com").toBuilder()
            .response(ResponseConfig.builder().text("changed").build())
            .build());
    assertThat(steps.proxy.reload(COMMON_CONFIG.toBuilder().sites(sites).build()).applied())
        .isTrue();
    steps.when_requesting("withprotection.com", "/");
    assertThat(deflectorCount(scrape(), "challenge_sent")).isEqualTo(2);
  }

  @Test
  void deflectorCountsPerIngress() {
    var sites = new HashMap<>(COMMON_CONFIG.sites());
    sites.put("second.com", sites.get("withprotection.com"));
    steps.finish_and_check_for_leaks();
    steps =
        new CompleteTest.Steps()
            .given_initialized_proxy_with(COMMON_CONFIG.toBuilder().sites(sites).build());
    steps.when_requesting("withprotection.com", "/");
    steps.when_requesting("second.com", "/");
    steps.when_requesting("second.com", "/x");
    var snapshots = scrape();
    assertThat(deflectorCount(snapshots, "withprotection.com", "challenge_sent")).isEqualTo(1);
    assertThat(deflectorCount(snapshots, "second.com", "challenge_sent")).isEqualTo(2);
  }

  /// Two proxies in one JVM have separate metrics, they used to share the global registry.
  @Test
  void proxiesHaveSeparateRegistries() {
    var other = new ProxyConstructBDDTest.Steps().given_the_configuration(COMMON_CONFIG);
    other.then_expect_initialized_without_exception();
    assertThat(other.proxy.metricsRegistry()).isNotSameAs(steps.proxy.metricsRegistry());
    steps.when_requesting("withprotection.com", "/");
    assertThat(deflectorCount(scrape(), "challenge_sent")).isEqualTo(1);
    var otherSnapshots = new MetricsAndMemoryTest.Snapshots(other.proxy.metricsRegistry().scrape());
    assertThat(otherSnapshots.containsSnapshot("sensepitch_deflector_requests").getDataPoints())
        .isEmpty();
    other.proxy.shutdown();
  }

  @Test
  void ingressConnections() {
    TrackIngressConnectionsHandler handler = new TrackIngressConnectionsHandler();
    PrometheusRegistry registry = new PrometheusRegistry();
    handler.registerCollectors(registry::register);
    EmbeddedChannel first = new EmbeddedChannel(handler);
    new EmbeddedChannel(handler);
    first.close();
    var snapshots = new MetricsAndMemoryTest.Snapshots(registry.scrape());
    assertThat(snapshots.counter("sensepitch_ingress_connections_opened").getValue()).isEqualTo(2);
    assertThat(snapshots.counter("sensepitch_ingress_connections_closed").getValue()).isEqualTo(1);
    assertThat(snapshots.gauge("sensepitch_ingress_connections_active").getValue()).isEqualTo(1);
  }

  /// Out of range status codes are put into the buckets 99 and 600, a timeout before a request was
  /// received is not counted.
  @Test
  void statusCodeIsClamped() {
    ExposeRequestCountPerStatusCodeHandler handler = new ExposeRequestCountPerStatusCodeHandler();
    PrometheusRegistry registry = new PrometheusRegistry();
    handler.registerCollectors(registry::register);
    handler.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 42));
    handler.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 777));
    handler.logRequest(TestRequestLogInfo.of(RequestLogInfo.NIL_METHOD, 408));
    var snapshots = new MetricsAndMemoryTest.Snapshots(registry.scrape());
    assertThat(histogramCount(snapshots, "example.com", "GET", "99")).isEqualTo(1);
    assertThat(histogramCount(snapshots, "example.com", "GET", "600")).isEqualTo(1);
    assertThat(
            snapshots
                .containsSnapshot("sensepitch_ingress_request_duration_seconds")
                .getDataPoints())
        .hasSize(2);
  }

  static long histogramCount(
      MetricsAndMemoryTest.Snapshots snapshots, String ingress, String method, String status) {
    var dataPoint =
        (HistogramSnapshot.HistogramDataPointSnapshot)
            snapshots.datapoint(
                "sensepitch_ingress_request_duration_seconds",
                "ingress",
                ingress,
                "method",
                method,
                "status_code",
                status);
    return dataPoint.getCount();
  }

  static double deflectorCount(MetricsAndMemoryTest.Snapshots snapshots, String outcome) {
    return deflectorCount(snapshots, "withprotection.com", outcome);
  }

  static double deflectorCount(
      MetricsAndMemoryTest.Snapshots snapshots, String ingress, String outcome) {
    return snapshots
        .counter("sensepitch_deflector_requests", "ingress", ingress, "outcome", outcome)
        .getValue();
  }
}
