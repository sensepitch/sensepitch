package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Label;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.List;

/// Logging facade of the proxy, with methods that render channel ids consistently.
///
/// Messages are forwarded to Log4j2 via [LogTarget], which also explains why diagnostics and
/// the request log go to different streams.
///
/// ## Levels
///
/// This is the central description of the log levels; the log methods refer to it.
///
/// - `ERROR`: a fault of ours or something an operator has to act on, e.g. a configuration that
///   cannot be applied or a request log export that loses records. Never for a request level
///   event, see below.
/// - `WARNING`: a problem in our own setup or state that may need action. For a request level
///   event only its first occurrence, see below.
/// - `INFO`: lifecycle, e.g. start, configuration, shutdown.
/// - `DEBUG`: what an operator needs to find the root cause of a request level event: channel,
///   phase, cause and stack trace. Off in the default configuration.
/// - `TRACE`: internals, several lines per request.
///
/// **Request level events are logged at `DEBUG`, errors included, except the first occurrence of
/// each kind at `WARNING`, see below, never at `INFO` or `ERROR`.** A request level event is one
/// that can happen for a request or a connection: a client or upstream misbehaving, an upstream
/// outage, a timeout, but also an unexpected exception that may be a bug of ours. Such an event can
/// repeat for every request, thousands of times per second, e.g. during an outage or an attack. At
/// `ERROR` it would flood the log, bury the lifecycle messages and cost I/O on the event loops.
///
/// **Every request level event is counted in a metric**, see [ProxyMetrics], or recorded in the
/// request log, so it is never silent: the metric is what an operator watches and alerts on, and
/// `DEBUG` is switched on to investigate. An `ERROR` is for faults that do not repeat per
/// request; one that may, e.g. a failing request log export, logs once and counts the rest.
///
/// **The first occurrence of each kind of request level event is logged at `WARNING`.** With
/// `DEBUG` off, as in the default configuration, a misconfiguration that makes every request fail,
/// e.g. an unreachable upstream during the initial setup, would otherwise leave no trace in the
/// log. The kind of an event is the series that counts it, the data point of a counter for its
/// label values, e.g. `sensepitch_upstream_errors_total{type="closed_in_flight",upstream="a"}`.
/// [#incrementAndWarnOnce(Counter, String[], Channel, String, Throwable)] counts the event and
/// logs it, so counting and logging cannot drift apart;
/// [#warnOnce(Counter, String[], Channel, String, Throwable)] only logs an event that another
/// place counts, e.g. the request outcome counted when the request is logged. The label values come from the label enums the counters
/// use, e.g. [ProxyMetrics.IngressErrorType]. The logger already names the class, so the kind does
/// not repeat it. The first occurrence of a series in the JVM is logged at `WARNING` and names the
/// series, where an operator finds the further ones, which are logged at `DEBUG`. So the log shows
/// each problem once per series, e.g. once per ingress, however often it repeats. An event
/// reported by another handler too, e.g. an upstream exception the [ExceptionHandler] counts, is
/// logged at `DEBUG` only by the one that does not count it.
///
/// Tracing is available by default: every logger is a [WithTracing] instance that asks Log4j2, so
/// the configured level decides, at the cost of a level check per trace call. The level is set in
/// the Log4j2 configuration, e.g. `LOG4J_CONFIGURATION_FILE=classpath:log4j2-trace.xml`.
///
/// For maximum performance the environment variable `SENSEPITCH_EDGE_DISABLE_TRACE=true` is a kill
/// switch: then every logger is an [ErrorOnly] instance whose [#isTraceEnabled()] is the constant
/// `false`. Since the loggers are kept in `static final` fields and only one implementation class
/// is ever instantiated in a JVM, the JIT inlines the check and removes trace code from the hot
/// path entirely.
///
/// @author Jens Wilke
public interface ProxyLogger {

  /// Kill switch for tracing, read once, so the logger implementation is fixed for the JVM lifetime
  boolean TRACE_DISABLED = "true".equalsIgnoreCase(System.getenv("SENSEPITCH_EDGE_DISABLE_TRACE"));

  static ProxyLogger get(String source) {
    return create(source, TRACE_DISABLED);
  }

  /// @param traceDisabled see [#TRACE_DISABLED]
  static ProxyLogger create(String source, boolean traceDisabled) {
    LogTarget target = LogTarget.of(source);
    if (traceDisabled) {
      return new ErrorOnly(source, target);
    }
    return new WithTracing(source, target);
  }

  /// Logger named after the fully qualified class name, so levels can be configured per package.
  /// Keep the result in a `static final` field, see the class documentation.
  static ProxyLogger get(Class<?> source) {
    return get(source.getName());
  }

  /// Whether `TRACE` is on, to skip building trace messages, see [ProxyLogger] "Levels"
  boolean isTraceEnabled();

  /// Logs a record at its own level, see [ProxyLogger] "Levels" for which level to use
  void log(LogInfo record);

  /// At `TRACE`, for internals, see [ProxyLogger] "Levels"
  void trace(String message);

  /// At `TRACE`, for internals, see [ProxyLogger] "Levels"
  void trace(Channel downstream, String message);

  /// At `TRACE`, the type of a message read from a channel, see [ProxyLogger] "Levels"
  void traceChannelRead(ChannelHandlerContext ctx, Object msg);

  /// At `TRACE`, for internals, see [ProxyLogger] "Levels"
  void trace(Channel downstream, Channel upstream, String message);

  /// At `INFO`, for lifecycle messages, see [ProxyLogger] "Levels"
  void info(String message);

  /// At `WARNING`, for a problem in our own setup, see [ProxyLogger] "Levels"
  void warn(String message);

  /// At `DEBUG` with the stack trace, for a request level event that is counted in a metric, see
  /// [ProxyLogger] "Levels". Prefer
  /// [#incrementAndWarnOnce(Counter, String[], Channel, String, Throwable)], which counts the
  /// event and shows the first occurrence at `WARNING`.
  void debug(Channel channel, String message, Throwable cause);

  /// The label values of a counter without labels
  String[] NO_LABELS = {};

  /// Counts a request level event in `counter` and logs it: at `WARNING` the first time it is
  /// counted in this series in the JVM, afterwards at `DEBUG`, see [ProxyLogger] "Levels"
  ///
  /// @param counter the counter of the event; with `labelValues` it designates the series and so
  ///     the kind of event
  /// @param labelValues the label values of the series, in the order of the label names of
  ///     `counter`, as for [Counter#labelValues(String...)], [#NO_LABELS] for a counter without
  ///     labels
  /// @param channel the channel of the event, or `null` if there is none
  /// @param cause the exception with the stack trace, or `null` if there is none
  void incrementAndWarnOnce(
      Counter counter, String[] labelValues, Channel channel, String message, Throwable cause);

  /// Counts and logs a request level event without a cause, see
  /// [#incrementAndWarnOnce(Counter, String[], Channel, String, Throwable)]
  default void incrementAndWarnOnce(
      Counter counter, String[] labelValues, Channel channel, String message) {
    incrementAndWarnOnce(counter, labelValues, channel, message, null);
  }

  /// Logs a request level event that another place counts in `counter`, without counting it, at
  /// `WARNING` or `DEBUG` as [#incrementAndWarnOnce(Counter, String[], Channel, String, Throwable)]
  void warnOnce(
      Counter counter, String[] labelValues, Channel channel, String message, Throwable cause);

  /// Logs a request level event without a cause that another place counts, see
  /// [#warnOnce(Counter, String[], Channel, String, Throwable)]
  default void warnOnce(Counter counter, String[] labelValues, Channel channel, String message) {
    warnOnce(counter, labelValues, channel, message, null);
  }

  /// The hint appended to the message of the first occurrence of an event, naming its series,
  /// e.g. `sensepitch_upstream_errors_total{type="closed_in_flight",upstream="a"}`. The label
  /// names of a [Counter] are not public, so they are taken from the data point with the same
  /// values in a snapshot of the counter, which costs only on the first occurrence.
  static String firstOccurrenceHint(Counter counter, String[] labelValues) {
    String series = counter.getPrometheusName() + "_total";
    if (labelValues.length > 0) {
      List<String> values = Arrays.stream(labelValues).sorted().toList();
      series +=
          counter.collect().getDataPoints().stream()
              .map(DataPointSnapshot::getLabels)
              .filter(
                  labels -> labels.stream().map(Label::getValue).sorted().toList().equals(values))
              .findFirst()
              .map(String::valueOf)
              .orElse("");
    }
    return " (first occurrence counted in " + series + ", further ones are logged at DEBUG)";
  }

  /// The series of `counter` for the label values, the key of the first occurrence. A counter
  /// without labels is its own series.
  private static CounterDataPoint dataPoint(Counter counter, String[] labelValues) {
    return labelValues.length == 0 ? counter : counter.labelValues(labelValues);
  }

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(Channel channel, Throwable cause);

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(Channel channel, String msg, Throwable cause);

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(Channel downstream, Channel upstream, String msg, Throwable cause);

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(Channel channel, String msg);

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(String msg);

  /// At `ERROR`, never for a request level event, see [ProxyLogger] "Levels"
  void error(String msg, Throwable cause);

  default String channelId(Channel channel) {
    return LogTarget.localChannelId(channel);
  }

  abstract class BaseLogger implements ProxyLogger {

    private final LogTarget target;

    /// @param source unused, the source is the name of the logger behind `target`
    public BaseLogger(String source, LogTarget target) {
      this.target = target;
    }

    LogTarget target() {
      return target;
    }

    @Override
    public void log(LogInfo record) {
      target.log(record);
    }

    @Override
    public void trace(String message) {
      if (isTraceEnabled()) {
        log(LogInfo.builder().level(Level.TRACE).message(message).build());
      }
    }

    @Override
    public void traceChannelRead(ChannelHandlerContext ctx, Object msg) {
      if (isTraceEnabled()) {
        Channel channel = ctx.channel();
        log(
            LogInfo.builder()
                .level(Level.TRACE)
                .channel(channel)
                .operation("channel read")
                .message(msg.getClass().getName())
                .build());
      }
    }

    @Override
    public void trace(Channel downstream, String message) {
      if (isTraceEnabled()) {
        log(
            LogInfo.builder()
                .level(Level.TRACE)
                .downstreamChannel(downstream)
                .message(message)
                .build());
      }
    }

    @Override
    public void trace(Channel downstream, Channel upstream, String message) {
      if (isTraceEnabled()) {
        log(
            LogInfo.builder()
                .level(Level.TRACE)
                .downstreamChannel(downstream)
                .upstreamChannel(upstream)
                .message(message)
                .build());
      }
    }

    public void error(Channel channel, Throwable cause) {
      log(LogInfo.builder().level(Level.ERROR).channel(channel).error(cause).build());
    }

    public void error(Channel channel, String message, Throwable cause) {
      log(
          LogInfo.builder()
              .level(Level.ERROR)
              .channel(channel)
              .message(message)
              .error(cause)
              .build());
    }

    public void error(Channel channel, String message) {
      log(LogInfo.builder().level(Level.ERROR).channel(channel).message(message).build());
    }

    public void error(Channel downstream, Channel upstream, String message, Throwable cause) {
      log(
          LogInfo.builder()
              .level(Level.ERROR)
              .downstreamChannel(downstream)
              .upstreamChannel(upstream)
              .message(message)
              .error(cause)
              .build());
    }

    public void error(String message) {
      log(LogInfo.builder().level(Level.ERROR).message(message).build());
    }

    @Override
    public void error(String msg, Throwable cause) {
      log(LogInfo.builder().level(Level.ERROR).message(msg).error(cause).build());
    }

    public void info(String message) {
      log(LogInfo.builder().level(Level.INFO).message(message).build());
    }

    @Override
    public void warn(String message) {
      log(LogInfo.builder().level(Level.WARNING).message(message).build());
    }

    @Override
    public void debug(Channel channel, String message, Throwable cause) {
      log(
          LogInfo.builder()
              .level(Level.DEBUG)
              .channel(channel)
              .message(message)
              .error(cause)
              .build());
    }

    @Override
    public void incrementAndWarnOnce(
        Counter counter, String[] labelValues, Channel channel, String message, Throwable cause) {
      CounterDataPoint point = dataPoint(counter, labelValues);
      point.inc();
      warnOnce(point, counter, labelValues, channel, message, cause);
    }

    @Override
    public void warnOnce(
        Counter counter, String[] labelValues, Channel channel, String message, Throwable cause) {
      warnOnce(dataPoint(counter, labelValues), counter, labelValues, channel, message, cause);
    }

    /// Logs at the level of the occurrence of `point`. The check for the first occurrence costs
    /// a hash lookup without locking, see [LogTarget#firstOccurrence(CounterDataPoint)], the
    /// message is only extended for the first one.
    private void warnOnce(
        CounterDataPoint point,
        Counter counter,
        String[] labelValues,
        Channel channel,
        String message,
        Throwable cause) {
      boolean first = target.firstOccurrence(point);
      log(
          LogInfo.builder()
              .level(first ? Level.WARNING : Level.DEBUG)
              .channel(channel)
              .message(first ? message + firstOccurrenceHint(counter, labelValues) : message)
              .error(cause)
              .build());
    }
  }

  class WithTracing extends BaseLogger {

    public WithTracing(String source, LogTarget target) {
      super(source, target);
    }

    @Override
    public boolean isTraceEnabled() {
      return target().isEnabled(Level.TRACE);
    }
  }

  class ErrorOnly extends BaseLogger {

    public ErrorOnly(String source, LogTarget target) {
      super(source, target);
    }

    @Override
    public boolean isTraceEnabled() {
      return false;
    }
  }
}
