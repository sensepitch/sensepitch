package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.model.registry.Collector;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/// Metrics of all circuit breakers, see [CircuitBreaker]. Every series has two labels: `component`,
/// what the breaker protects, e.g. `requestLog`, and `name`, the instance within the component,
/// e.g. the destination name, the same value as the `destination` label of the request log
/// metrics. The basic names follow the resilience4j Micrometer binding with the prefix
/// `sensepitch_circuitbreaker_`, so its dashboards carry over:
///
/// - `_state{state}`: 1 for the current state, 0 for the others
/// - `_calls_total{kind}`: each call once, `successful`, `failed`, `ignored` or `not_permitted`
///
/// Added, because the breaker counts consecutive failures and has no sliding window:
///
/// - `_transitions_total{from,to,reason}`: each state change, with the error code for a trip
/// - `_errors_total{error}`: each failed or ignored call by error code, e.g. `http_503`
/// - `_consecutive_failures`: the failures the backoff is based on
/// - `_start_time_seconds`, `_last_trip_time_seconds`, `_last_restart_time_seconds`: wall clock,
///   `0` if it did not happen yet
///
/// The gauges report the registered breakers only, see [#remove(CircuitBreaker)]; the counters
/// keep their values.
///
/// @author Jens Wilke
public class CircuitBreakerMetrics implements HasMultipleMetrics {

  static final String PREFIX = "sensepitch_circuitbreaker_";
  static final String COMPONENT_LABEL = "component";
  static final String NAME_LABEL = "name";

  /// A registered breaker with its label values
  private record Entry(String component, String name, CircuitBreaker breaker) {}

  private final List<Entry> breakers = new CopyOnWriteArrayList<>();

  private final Counter calls =
      Counter.builder()
          .name(PREFIX + "calls")
          .help("Calls by outcome: successful, failed, ignored, or not permitted by the breaker")
          .labelNames(COMPONENT_LABEL, NAME_LABEL, "kind")
          .build();

  private final Counter transitions =
      Counter.builder()
          .name(PREFIX + "transitions")
          .help("State changes of the circuit breaker, the reason of a trip is the error code")
          .labelNames(COMPONENT_LABEL, NAME_LABEL, "from", "to", "reason")
          .build();

  private final Counter errors =
      Counter.builder()
          .name(PREFIX + "errors")
          .help("Failed and ignored calls by error code")
          .labelNames(COMPONENT_LABEL, NAME_LABEL, "error")
          .build();

  private final GaugeWithCallback state =
      GaugeWithCallback.builder()
          .name(PREFIX + "state")
          .help("The state of the circuit breaker, 1 for the current one")
          .labelNames(COMPONENT_LABEL, NAME_LABEL, "state")
          .callback(
              cb -> {
                for (Entry entry : breakers) {
                  CircuitBreaker.State current = entry.breaker().state();
                  for (CircuitBreaker.State candidate : CircuitBreaker.State.values()) {
                    cb.call(
                        candidate == current ? 1 : 0,
                        entry.component(),
                        entry.name(),
                        label(candidate));
                  }
                }
              })
          .build();

  private final GaugeWithCallback consecutiveFailures =
      gauge(
          "consecutive_failures",
          "Failed calls in a row, the backoff grows with them",
          CircuitBreaker::failures);

  private final GaugeWithCallback startTime =
      timeGauge(
          "start_time_seconds",
          "When the circuit breaker was created",
          CircuitBreaker::startMillis);

  private final GaugeWithCallback lastTripTime =
      timeGauge(
          "last_trip_time_seconds",
          "When the circuit breaker last opened after being closed, 0 if never",
          CircuitBreaker::lastTripMillis);

  private final GaugeWithCallback lastRestartTime =
      timeGauge(
          "last_restart_time_seconds",
          "When the circuit breaker last closed after a trip, 0 if never",
          CircuitBreaker::lastRestartMillis);

  /// The counters of a breaker, to pass to its constructor. Creates the data points of the calls,
  /// so they are exported with zero before the first call.
  ///
  /// @param component what the breaker protects, e.g. `requestLog`
  /// @param name the instance within the component, e.g. the destination name
  CircuitBreaker.Events events(String component, String name) {
    return new Recorder(component, name);
  }

  /// Reports the gauges of the breaker under the labels, until removed
  void add(String component, String name, CircuitBreaker breaker) {
    breakers.add(new Entry(component, name, breaker));
  }

  /// Stops reporting the gauges of the breaker, e.g. of a destination removed by a reload. Its
  /// counters keep their values.
  void remove(CircuitBreaker breaker) {
    breakers.removeIf(entry -> entry.breaker() == breaker);
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    consumer.accept(state);
    consumer.accept(calls);
    consumer.accept(transitions);
    consumer.accept(errors);
    consumer.accept(consecutiveFailures);
    consumer.accept(startTime);
    consumer.accept(lastTripTime);
    consumer.accept(lastRestartTime);
  }

  static String label(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private GaugeWithCallback gauge(String name, String help, ToLongFunction<CircuitBreaker> value) {
    return GaugeWithCallback.builder()
        .name(PREFIX + name)
        .help(help)
        .labelNames(COMPONENT_LABEL, NAME_LABEL)
        .callback(
            cb -> {
              for (Entry entry : breakers) {
                cb.call(value.applyAsLong(entry.breaker()), entry.component(), entry.name());
              }
            })
        .build();
  }

  /// A gauge of a wall clock time in milliseconds, exported in seconds
  private GaugeWithCallback timeGauge(
      String name, String help, ToLongFunction<CircuitBreaker> millis) {
    return GaugeWithCallback.builder()
        .name(PREFIX + name)
        .help(help)
        .labelNames(COMPONENT_LABEL, NAME_LABEL)
        .callback(
            cb -> {
              for (Entry entry : breakers) {
                cb.call(
                    millis.applyAsLong(entry.breaker()) / 1000.0, entry.component(), entry.name());
              }
            })
        .build();
  }

  /// Counts the events of one breaker. The data points of the calls are cached, a refusal is
  /// counted per attempt and must not allocate; transitions and errors are rare and looked up.
  private final class Recorder implements CircuitBreaker.Events {

    private final String component;
    private final String name;
    private final CounterDataPoint[] callsByKind =
        new CounterDataPoint[CircuitBreaker.Kind.values().length];

    Recorder(String component, String name) {
      this.component = component;
      this.name = name;
      for (CircuitBreaker.Kind kind : CircuitBreaker.Kind.values()) {
        callsByKind[kind.ordinal()] = calls.labelValues(component, name, label(kind));
      }
    }

    @Override
    public void call(CircuitBreaker.Kind kind) {
      callsByKind[kind.ordinal()].inc();
    }

    @Override
    public void error(String error) {
      errors.labelValues(component, name, error).inc();
    }

    @Override
    public void transition(CircuitBreaker.State from, CircuitBreaker.State to, String reason) {
      transitions.labelValues(component, name, label(from), label(to), reason).inc();
    }
  }
}
