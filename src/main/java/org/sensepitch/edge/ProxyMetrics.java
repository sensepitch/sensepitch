package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/// Metrics of the proxy core: ingress requests and errors, deflector, request outcomes.
///
/// Every ingress metric (`sensepitch_ingress_*`, and every metric counting ingress requests, e.g.
/// `sensepitch_deflector_requests`) has the label `ingress`, the `Host` header as sanitized by
/// [SanitizeHostHandler]: a known host or one of [SanitizeHostHandler#SPECIAL_HOSTS], so the
/// number of label values is bounded. Take it with [#ingress(HttpRequest)].
///
/// @author Jens Wilke
public class ProxyMetrics implements HasMultipleMetrics {

  private final MetricSet metricSet = new MetricSet();

  public final Counter ingressErrorCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_request_errors")
              .labelNames("phase", "type")
              .build());

  public final Counter ingressReceiveTimeoutCounter =
      metricSet.add(
          Counter.builder().name("sensepitch_ingress_receive_timeout").labelNames("phase").build());

  public final CounterDataPoint ingressReceiveTimeoutFirstRequest =
      ingressReceiveTimeoutCounter.labelValues("first_request");

  public final CounterDataPoint ingressReceiveTimeoutKeepAlive =
      ingressReceiveTimeoutCounter.labelValues("keep_alive");

  /// Requests by ingress and [RequestOutcome], counted once per request when it is logged, so the
  /// counts match the request log, see [#countRequestOutcome]. The outcome label values are the
  /// fixed set of [RequestOutcome#value()].
  public final Counter requestOutcomeCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_request_outcomes")
              .help("Requests by ingress and outcome, counted when the request is logged")
              .labelNames("ingress", "outcome")
              .build());

  /// Counts a logged request, called by [RequestLoggingHandler] with the final outcome
  public void countRequestOutcome(HttpRequest request, RequestOutcome outcome) {
    requestOutcomeCounter.labelValues(ingress(request), outcome.value()).inc();
  }

  /// Requests handled by a [Deflector], by ingress and outcome, see [#countDeflectorRequest]
  public final Counter deflectorRequestCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_deflector_requests")
              .help("Requests handled by the deflector, by ingress and outcome")
              .labelNames("ingress", "outcome")
              .build());

  /// Outcome of a request handled by a [Deflector], the label value is the lower case name
  public enum DeflectorOutcome {
    /// Valid admission cookie, request passed on
    PASSED,
    /// No admission cookie, but bypass configured or crawler detected, request passed on
    BYPASSED,
    /// Method other than GET, without admission or bypass, refused with an empty 403
    METHOD_REJECTED,
    /// Challenge page sent
    CHALLENGE_SENT,
    /// Correct challenge answer, admission cookie issued
    ANSWER_ACCEPTED,
    /// Wrong or incomplete challenge answer
    ANSWER_REJECTED;

    final String label = name().toLowerCase(Locale.ROOT);

    /// Label value and logged value, the lower case name
    public String value() {
      return label;
    }
  }

  /// Counts the request for its ingress, see [#ingress(HttpRequest)]
  public void countDeflectorRequest(HttpRequest request, DeflectorOutcome outcome) {
    deflectorRequestCounter.labelValues(ingress(request), outcome.label).inc();
  }

  /// The `ingress` label value of a request, see the class doc. The host header is missing only
  /// when [SanitizeHostHandler] is not in the pipeline, e.g. in tests.
  static String ingress(HttpRequest request) {
    String ingress = request.headers().get(HttpHeaderNames.HOST);
    return ingress != null ? ingress : SanitizeHostHandler.MISSING_HOST;
  }

  public final LongAdder ingressRequestsStarted = new LongAdder();
  public final LongAdder ingressRequestsCompleted = new LongAdder();
  public final LongAdder ingressRequestsAborted = new LongAdder();

  /// Requests in flight per upstream name, for the shutdown report, see [Proxy#shutdown()]
  private final Map<String, LongAdder> upstreamRequestsInFlight = new ConcurrentHashMap<>();

  /// Requests started minus completed and aborted
  public long ingressRequestsInFlight() {
    return ingressRequestsStarted.sum()
        - ingressRequestsCompleted.sum()
        - ingressRequestsAborted.sum();
  }

  /// Counter of the requests in flight to the upstream, incremented when the upstream is selected
  /// and decremented when the request is logged
  LongAdder upstreamRequestsInFlight(Upstream upstream) {
    String name = upstream.name() != null ? upstream.name() : String.valueOf(upstream.target());
    return upstreamRequestsInFlight.computeIfAbsent(name, k -> new LongAdder());
  }

  /// Upstreams with requests in flight, by name
  Map<String, Long> upstreamRequestsInFlight() {
    Map<String, Long> inFlight = new TreeMap<>();
    upstreamRequestsInFlight.forEach(
        (name, count) -> {
          long sum = count.sum();
          if (sum > 0) {
            inFlight.put(name, sum);
          }
        });
    return inFlight;
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    metricSet.forEach(consumer);
    consumer.accept(
        () ->
            GaugeSnapshot.builder()
                .name("sensepitch_edge_server_live")
                .help(
                    "Always 1, might be 0 in the future when the server is not accepting connections, e.g. warming up")
                .dataPoint(
                    GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .value(1)
                        .labels(Labels.EMPTY)
                        .build())
                .build());
    consumer.accept(
        () ->
            CounterSnapshot.builder()
                .name("sensepitch_ingress_requests_started")
                .help("Increments as soon as an HTTP header from a client is received")
                .dataPoint(
                    CounterSnapshot.CounterDataPointSnapshot.builder()
                        .value(ingressRequestsStarted.sum())
                        .build())
                .build());
    consumer.accept(
        () ->
            CounterSnapshot.builder()
                .name("sensepitch_ingress_requests_completed")
                .help("Increments once the response was received by the client")
                .dataPoint(
                    CounterSnapshot.CounterDataPointSnapshot.builder()
                        .value(ingressRequestsCompleted.sum())
                        .build())
                .build());
    consumer.accept(
        () ->
            CounterSnapshot.builder()
                .name("sensepitch_ingress_requests_aborted")
                .help(
                    "The response could not be sent to the client, e.g. when the connection is reset")
                .dataPoint(
                    CounterSnapshot.CounterDataPointSnapshot.builder()
                        .value(ingressRequestsAborted.sum())
                        .build())
                .build());
    consumer.accept(
        () ->
            GaugeSnapshot.builder()
                .name("sensepitch_ingress_requests_in_flight")
                .help("Number of requests currently in flight")
                .dataPoint(
                    GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .value(ingressRequestsInFlight())
                        .build())
                .build());
  }
}
