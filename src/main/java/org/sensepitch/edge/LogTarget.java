package org.sensepitch.edge;

import io.netty.channel.Channel;
import java.lang.System.Logger.Level;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Sink of the diagnostic messages produced by [ProxyLogger].
///
/// Diagnostic messages go to Log4j2, which the shipped `log4j2.xml` sends to standard error
/// without buffering. The request log, instead, is written directly to standard out, see
/// [RequestLogger]. The split is deliberate:
///
/// - Diagnostic messages must be visible at once. The last message before a crash or a hang is
///   the one you need, so they are never held back in a buffer.
/// - The request log is machine-readable data for a log collector. Passing it through a logging
///   framework would wrap the JSON record into another layout. Delaying it by a buffer or losing
///   the last lines on a crash is acceptable in exchange for throughput.
///
/// Keeping both on separate streams means a collector can read standard out as pure request log
/// without filtering.
///
/// @author Jens Wilke
public interface LogTarget {

  /// Target of the logger with the given name, forwarding to Log4j2 unless replaced, see
  /// [Factory]
  static LogTarget of(String loggerName) {
    return Factory.current.apply(loggerName);
  }

  /// Creates the targets of all loggers. A logger asks once when it is created, mostly for a
  /// `static final` field, so the indirection costs nothing when logging. Tests replace the
  /// factory before the first logger is created, to collect the messages in memory instead of
  /// passing them through Log4j2.
  final class Factory {

    private static volatile Function<String, LogTarget> current = Factory::log4j;

    private Factory() {}

    /// The default: a target forwarding to the Log4j2 logger with the given name
    static LogTarget log4j(String loggerName) {
      return new Log4jOutput(LogManager.getLogger(loggerName));
    }

    /// Loggers created from now on get their target from the given factory, existing loggers
    /// keep theirs
    static void replace(Function<String, LogTarget> factory) {
      current = factory;
    }
  }

  static String localChannelId(Channel ch) {
    if (ch == null) {
      return "null";
    }
    return ch.id().asShortText();
  }

  /// Renders the message text of a record, without the throwable. Time, thread and source are
  /// added by the logging layout.
  static String format(LogInfo record) {
    StringBuilder sb = new StringBuilder();
    boolean needSpace = false;
    if (record.channel() != null) {
      sb.append(localChannelId(record.channel()));
      needSpace = true;
    }
    if (record.downstreamChannel() != null) {
      sb.append(localChannelId(record.downstreamChannel()));
      needSpace = true;
    }
    if (record.upstreamChannel() != null) {
      sb.append('>');
      sb.append(localChannelId(record.upstreamChannel()));
      needSpace = true;
    }
    if (needSpace) {
      sb.append(' ');
    }
    if (record.operation() != null) {
      sb.append(record.operation());
      sb.append(' ');
    }
    if (record.message() != null) {
      sb.append(record.message());
    }
    return sb.toString().trim();
  }

  /// True, if records of the level are processed
  boolean isEnabled(Level level);

  void log(LogInfo info);

  /// Forwards to a Log4j2 logger. The log level of the record is mapped to the Log4j2 level.
  class Log4jOutput implements LogTarget {

    private final Logger logger;

    public Log4jOutput(Logger logger) {
      this.logger = logger;
    }

    static org.apache.logging.log4j.Level toLog4j(Level level) {
      if (level == null) {
        return org.apache.logging.log4j.Level.INFO;
      }
      return switch (level) {
        case ALL -> org.apache.logging.log4j.Level.ALL;
        case TRACE -> org.apache.logging.log4j.Level.TRACE;
        case DEBUG -> org.apache.logging.log4j.Level.DEBUG;
        case INFO -> org.apache.logging.log4j.Level.INFO;
        case WARNING -> org.apache.logging.log4j.Level.WARN;
        case ERROR -> org.apache.logging.log4j.Level.ERROR;
        case OFF -> org.apache.logging.log4j.Level.OFF;
      };
    }

    @Override
    public boolean isEnabled(Level level) {
      return logger.isEnabled(toLog4j(level));
    }

    @Override
    public void log(LogInfo record) {
      org.apache.logging.log4j.Level level = toLog4j(record.level());
      // check first, so the message is not rendered when the level is filtered
      if (logger.isEnabled(level)) {
        logger.log(level, format(record), record.error());
      }
    }
  }
}
