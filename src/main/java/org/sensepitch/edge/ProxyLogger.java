package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.lang.System.Logger.Level;

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
/// log. A kind of event is a [RequestProblem], a constant of the class that reports it, logged
/// with [#requestProblem(RequestProblem, Channel, String, Throwable)]. The first occurrence in the
/// JVM is logged at `WARNING` and names the metric that counts the further ones, which are logged
/// at `DEBUG`. So the log shows each kind of problem once, however often it repeats.
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
  /// [ProxyLogger] "Levels". Prefer [#requestProblem(RequestProblem, Channel, String, Throwable)],
  /// which shows the first occurrence at `WARNING`.
  void debug(Channel channel, String message, Throwable cause);

  /// Request level event of the kind `problem`, counted in the metric the problem names: at
  /// `WARNING` the first time the problem occurs in the JVM, afterwards at `DEBUG`, see
  /// [ProxyLogger] "Levels"
  ///
  /// @param channel the channel of the event, or `null` if there is none
  /// @param cause the exception with the stack trace, or `null` if there is none
  void requestProblem(RequestProblem problem, Channel channel, String message, Throwable cause);

  /// Request level event of the kind `problem` without a cause, see
  /// [#requestProblem(RequestProblem, Channel, String, Throwable)]
  default void requestProblem(RequestProblem problem, Channel channel, String message) {
    requestProblem(problem, channel, message, null);
  }

  /// A kind of request level event, see [ProxyLogger] "Levels". Keep it in a `static final` field
  /// of the class that reports it, the key must be unique in the JVM.
  ///
  /// @param key unique name, `<simple class name>.<problem name>`, e.g.
  ///     `ExceptionHandler.handshakeError`
  /// @param countedIn where the occurrences are counted, the metric with its labels, named in the
  ///     first log line so an operator knows where to look for the further ones
  record RequestProblem(String key, String countedIn) {

    /// The hint appended to the message of the first occurrence
    String firstOccurrenceHint() {
      return " (first occurrence of "
          + key
          + ", further ones are logged at DEBUG and counted in "
          + countedIn
          + ")";
    }
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

    /// The check for the first occurrence costs a hash lookup without locking and allocation, see
    /// [LogTarget#firstOccurrence(String)], the message is only extended for the first one.
    @Override
    public void requestProblem(
        RequestProblem problem, Channel channel, String message, Throwable cause) {
      boolean first = target.firstOccurrence(problem.key());
      log(
          LogInfo.builder()
              .level(first ? Level.WARNING : Level.DEBUG)
              .channel(channel)
              .message(first ? message + problem.firstOccurrenceHint() : message)
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
