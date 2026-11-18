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
/// - `WARNING`: a problem in our own setup or state that may need action, never for a misbehaving
///   client or upstream.
/// - `INFO`: lifecycle, e.g. start, configuration, shutdown.
/// - `DEBUG`: what an operator needs to find the root cause of a request level event: channel,
///   phase, cause and stack trace. Off in the default configuration.
/// - `TRACE`: internals, several lines per request.
///
/// **Request level events are logged at `DEBUG`, never at `INFO` or above, errors included.** A
/// request level event is one that can happen for a request or a connection: a client or upstream
/// misbehaving, an upstream outage, a timeout, but also an unexpected exception that may be a bug
/// of ours. Such an event can repeat for every request, thousands of times per second, e.g.
/// during an outage or an attack. At `ERROR` it would flood the log, bury the lifecycle messages
/// and cost I/O on the event loops.
///
/// **Every request level event is counted in a metric**, see [ProxyMetrics], or recorded in the
/// request log, so it is never silent: the metric is what an operator watches and alerts on, and
/// `DEBUG` is switched on to investigate. An `ERROR` is for faults that do not repeat per
/// request; one that may, e.g. a failing request log export, logs once and counts the rest.
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

  /// At `DEBUG`, for a request level event that is counted in a metric, see [ProxyLogger] "Levels"
  void debug(Channel channel, String message);

  /// At `DEBUG` with the stack trace, for a request level event that is counted in a metric, see
  /// [ProxyLogger] "Levels"
  void debug(Channel channel, String message, Throwable cause);

  /// At `DEBUG` with the stack trace, for a request level event without a channel, see
  /// [ProxyLogger] "Levels"
  void debug(String message, Throwable cause);

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
    public void debug(Channel channel, String message) {
      log(LogInfo.builder().level(Level.DEBUG).channel(channel).message(message).build());
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
    public void debug(String message, Throwable cause) {
      log(LogInfo.builder().level(Level.DEBUG).message(message).error(cause).build());
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
