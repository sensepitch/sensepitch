package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;

/**
 * @author Jens Wilke
 */
public interface ProxyLogger {

  static ProxyLogger get(String source) {
    String debugFlag = System.getenv("SENSEPITCH_EDGE_LOG_LEVEL");
    if (debugFlag != null) {
      return new WithTracing(source, LogTarget.INSTANCE);
    }
    return new ErrorOnly(source, LogTarget.INSTANCE);
  }

  static ProxyLogger get(Class<?> source) {
    String sourceName = source.getSimpleName();
    if (sourceName.isEmpty()) {
      sourceName = source.getName();
    }
    return get(sourceName);
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
    private final String source;
    private final ReportErrorOnce downstreamOnce = new ReportErrorOnce(this);

    public BaseLogger(String source, LogTarget target) {
      this.target = target;
      this.source = source;
    }

    @Override
    public void downstreamError(Channel downstream, String msg, Throwable cause) {
      String remoteHost = "-";
      if (downstream.remoteAddress() instanceof InetSocketAddress) {
        InetSocketAddress addr = (InetSocketAddress) downstream.remoteAddress();
        remoteHost = addr.getAddress().getHostAddress();
      }
      error(downstream, "DOWNSTREAM ERROR " + msg + " " + remoteHost + " " + cause, cause);
      // FIXME
      // downstreamOnce.report(msg + " (subsequent errors suppressed) " + cause.getMessage());
    }

    @Override
    public void upstreamError(Channel upstream, String msg, Throwable cause) {
      String remoteHost = "-";
      if (upstream.remoteAddress() instanceof InetSocketAddress) {
        InetSocketAddress addr = (InetSocketAddress) upstream.remoteAddress();
        remoteHost = addr.getAddress().getHostAddress();
      }
      error(upstream, "UPSTREAM ERROR " + msg + " " + remoteHost + " " + cause);
      // FIXME
    }

    @Override
    public void log(LogInfo record) {
      target.log(source, record);
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
      log(LogInfo.builder().level(Level.ERROR).channel(channel).build());
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
      return true;
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
