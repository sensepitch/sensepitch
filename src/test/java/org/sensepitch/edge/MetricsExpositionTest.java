package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// Pins the exact text of the metrics export, for every kind of metric the proxy uses, against
/// golden files in `src/test/resources/metrics/`. The metrics come from the production classes,
/// the output from the real HTTP exporter, see [MetricsServer].
///
/// A difference in a golden file is a visible change for dashboards and alerts, e.g. after a
/// Prometheus client update or a change of a metric definition. Review it, then regenerate the
/// files with `./mvnw test -Dtest=MetricsExpositionTest -Dmetrics.golden.update=true`.
///
/// @author Jens Wilke
class MetricsExpositionTest {

  static final Path GOLDEN_DIR =
      Path.of(System.getProperty("basedir", "."), "src/test/resources/metrics");
  static final boolean UPDATE = Boolean.getBoolean("metrics.golden.update");

  /// What curl or a browser gets
  static final String TEXT_FORMAT = "text/plain;version=0.0.4";

  /// What the Prometheus server negotiates
  static final String OPEN_METRICS = "application/openmetrics-text;version=1.0.0";

  final HttpClient client = HttpClient.newHttpClient();

  @Test
  void textFormat() throws Exception {
    assertGolden("exposition-text.txt", normalize(scrape(proxyMetricsRegistry(), TEXT_FORMAT)));
  }

  @Test
  void openMetricsFormat() throws Exception {
    assertGolden(
        "exposition-openmetrics.txt", normalize(scrape(proxyMetricsRegistry(), OPEN_METRICS)));
  }

  /// Names and snapshot types only, the values and labels depend on the machine and the garbage
  /// collector. Taken from the registry and not from the export, because the export leaves out
  /// metrics without data points, e.g. `jvm_memory_pool_allocated_bytes` until the first garbage
  /// collection. Expects Linux and `-XX:NativeMemoryTracking=summary`, as set for surefire in the
  /// `pom.xml`.
  @Test
  void jvmMetricNames() throws Exception {
    PrometheusRegistry registry = new PrometheusRegistry();
    PrometheusConfig cfg = PrometheusConfig.builder().port(0).enableJvmMetrics(true).build();
    try (MetricsServer ignored = MetricsServer.start(cfg, registry)) {
      String names =
          registry.scrape().stream()
              .map(
                  snapshot ->
                      snapshot.getMetadata().getPrometheusName()
                          + " "
                          + snapshot.getClass().getSimpleName())
              .sorted()
              .collect(Collectors.joining("\n", "", "\n"));
      assertGolden("jvm-metric-names.txt", names);
    }
  }

  /// All metric kinds of the proxy, with fixed values
  static PrometheusRegistry proxyMetricsRegistry() {
    PrometheusRegistry registry = new PrometheusRegistry();

    ProxyMetrics proxyMetrics = new ProxyMetrics();
    proxyMetrics.registerCollectors(registry::register);
    proxyMetrics.ingressRequestsStarted.add(5);
    proxyMetrics.ingressRequestsCompleted.add(3);
    proxyMetrics.ingressRequestsAborted.add(1);
    proxyMetrics.ingressErrorCounter.labelValues("upstream", "connect").inc();
    proxyMetrics.ingressReceiveTimeoutKeepAlive.inc(2);
    HttpRequest deflected = TestRequestLogInfo.of(HttpMethod.GET, 200).request();
    proxyMetrics.countDeflectorRequest(deflected, ProxyMetrics.DeflectorOutcome.CHALLENGE_SENT);
    proxyMetrics.countDeflectorRequest(deflected, ProxyMetrics.DeflectorOutcome.CHALLENGE_SENT);
    proxyMetrics.countDeflectorRequest(deflected, ProxyMetrics.DeflectorOutcome.PASSED);

    ExposeRequestCountPerStatusCodeHandler perStatus = new ExposeRequestCountPerStatusCodeHandler();
    perStatus.registerCollectors(registry::register);
    perStatus.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    TestRequestLogInfo flavored = TestRequestLogInfo.of(HttpMethod.POST, 404);
    flavored.request().headers().set(Deflector.TRAFFIC_TYPE_HEADER, "human");
    flavored.request().headers().set(IpTraitsHandler.COUNTRY_HEADER, "DE");
    perStatus.logRequest(flavored);

    TrackIngressConnectionsHandler connections = new TrackIngressConnectionsHandler();
    connections.registerCollectors(registry::register);
    EmbeddedChannel first = new EmbeddedChannel(connections);
    new EmbeddedChannel(connections);
    first.close();

    RequestLogMetrics requestLogMetrics = new RequestLogMetrics();
    requestLogMetrics.registerCollectors(registry::register);
    requestLogMetrics.add(new FixedQueue("stdout", 7, -1));
    requestLogMetrics.add(new FixedQueue("otlp", 3, 4096));
    requestLogMetrics.dropped.labelValues("otlp").inc(2);
    requestLogMetrics.exportRetries.labelValues("otlp").inc();
    requestLogMetrics.exportFailed.labelValues("otlp").inc();
    requestLogMetrics.sentRecords.labelValues("otlp").inc(5);
    requestLogMetrics.sentBytes.labelValues("otlp").inc(1234);
    // an outage and its recovery, with fixed clocks
    long[] wallMillis = {1_700_000_000_000L};
    CircuitBreaker breaker =
        new CircuitBreaker(
            new Backoff(1_000, 30_000, bound -> 0),
            5_000,
            requestLogMetrics.circuitBreakers.events("requestLog", "otlp"),
            () -> 0,
            () -> wallMillis[0]);
    requestLogMetrics.circuitBreakers.add("requestLog", "otlp", breaker);
    wallMillis[0] += 1_000;
    breaker.onFailure(0, "http_503");
    breaker.tryAcquire();
    breaker.skipBackoff();
    breaker.tryAcquire();
    wallMillis[0] += 1_000;
    breaker.onSuccess();
    breaker.onIgnored("http_400");

    new NettyAllocatorMetrics().registerCollectors(registry::register);
    return registry;
  }

  record FixedQueue(String destination, int queueSize, long bufferedBytes)
      implements RequestLogMetrics.QueueSource {}

  String scrape(PrometheusRegistry registry, String accept) throws Exception {
    return scrape(registry, PrometheusConfig.builder().port(0).build(), accept);
  }

  String scrape(PrometheusRegistry registry, PrometheusConfig cfg, String accept) throws Exception {
    try (MetricsServer server = MetricsServer.start(cfg, registry)) {
      var request =
          java.net.http.HttpRequest.newBuilder(
                  URI.create("http://localhost:" + server.port() + "/metrics"))
              .header(HttpHeaderNames.ACCEPT.toString(), accept)
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      return "# Content-Type: "
          + response.headers().firstValue("Content-Type").orElse("")
          + "\n"
          + response.body();
    }
  }

  /// Replaces the values that differ between runs: creation timestamps and the memory used by the
  /// netty allocators
  static String normalize(String text) {
    return Arrays.stream(text.split("\n", -1))
        .map(line -> line.replaceFirst("^(\\S*_created(\\{.*})?) \\S+$", "$1 <created>"))
        .map(
            line ->
                line.replaceFirst(
                    "^(" + NettyAllocatorMetrics.METRIC_NAME + "\\{.*}) \\S+$", "$1 <bytes>"))
        .collect(Collectors.joining("\n"));
  }

  static void assertGolden(String fileName, String actual) throws IOException {
    Path file = GOLDEN_DIR.resolve(fileName);
    if (UPDATE) {
      Files.createDirectories(GOLDEN_DIR);
      Files.writeString(file, actual, StandardCharsets.UTF_8);
      return;
    }
    assertThat(actual)
        .describedAs("metrics export differs from %s, see class doc", file)
        .isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
  }
}
