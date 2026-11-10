package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;

/// Logging facade of the proxy, with methods that render channel ids consistently.
///
/// Messages are forwarded to Log4j2 via [LogTarget], which also explains why diagnostics and
/// the request log go to different streams.
///
/// Tracing is gated twice. Unless the environment variable `SENSEPITCH_EDGE_LOG_LEVEL` is set,
/// every logger is an [ErrorOnly] instance whose [#isTraceEnabled()] is the constant `false`.
/// Since the loggers are kept in `static final` fields and only this one implementation class is
/// ever instantiated, the JIT inlines the check and removes trace code from the hot path
/// entirely. When the variable is set, [WithTracing] asks Log4j2, so the configured level
/// decides, at the cost of a level check per call.
///
/// @author Jens Wilke
public interface ProxyLogger {

  /// Read once, so the logger implementation is fixed for the JVM lifetime
  boolean TRACE_POSSIBLE = System.getenv("SENSEPITCH_EDGE_LOG_LEVEL") != null;

  static ProxyLogger get(String source) {
    LogTarget target = LogTarget.of(source);
    if (TRACE_POSSIBLE) {
      return new WithTracing(source, target);
    }
    return new ErrorOnly(source, target);
  }

  /// Logger named after the fully qualified class name, so levels can be configured per package.
  /// Keep the result in a `static final` field, see the class documentation.
  static ProxyLogger get(Class<?> source) {
    return get(source.getName());
  }

  boolean isTraceEnabled();

  void log(LogInfo record);

  void trace(String message);

  void trace(Channel downstream, String message);

  void traceChannelRead(ChannelHandlerContext ctx, Object msg);

  void trace(Channel downstream, Channel upstream, String message);

  void info(String message);

  void error(Channel channel, Throwable cause);

  void error(Channel channel, String msg, Throwable cause);

  void error(Channel downstream, Channel upstream, String msg, Throwable cause);

  void error(Channel channel, String msg);

  void error(String msg);

  void error(String msg, Throwable cause);

  void downstreamError(Channel downstream, String msg, Throwable cause);

  void upstreamError(Channel upstream, String msg, Throwable cause);

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
    public void downstreamError(Channel downstream, String msg, Throwable cause) {
      String remoteHost = "-";
      if (downstream.remoteAddress() instanceof InetSocketAddress) {
        InetSocketAddress addr = (InetSocketAddress) downstream.remoteAddress();
        remoteHost = addr.getAddress().getHostAddress();
      }
      error(downstream, "DOWNSTREAM ERROR " + msg + " " + remoteHost + " " + cause, cause);
    }

    @Override
    public void upstreamError(Channel upstream, String msg, Throwable cause) {
      String remoteHost = "-";
      if (upstream.remoteAddress() instanceof InetSocketAddress) {
        InetSocketAddress addr = (InetSocketAddress) upstream.remoteAddress();
        remoteHost = addr.getAddress().getHostAddress();
      }
      error(upstream, "UPSTREAM ERROR " + msg + " " + remoteHost + " " + cause);
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
