package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Collects the Log4j2 events of the whole JVM in memory for the duration of a test. Only for
/// tests of the way into Log4j2 itself: the forwarding by [LogTarget.Log4jOutput], and Netty and
/// JUL, which log to Log4j2 directly. Everything else asserts on the messages of the proxy's
/// loggers with [LogCapture], which is separate per thread.
///
/// The test class must be `@Isolated`, which is checked.
///
/// The in-memory appender is attached to the root logger before each test and removed
/// afterwards. It captures events at `INFO` and above.
///
/// @author Jens Wilke
public class Log4jCapture implements BeforeEachCallback, AfterEachCallback {

  private final Appender appender = new Appender();

  @Override
  public void beforeEach(ExtensionContext context) {
    LogCapture.requireIsolated(context, "Log4jCapture");
    clear();
    appender.start();
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    config.getRootLogger().addAppender(appender, Level.INFO, null);
    ctx.updateLoggers();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    LoggerConfig root = ctx.getConfiguration().getRootLogger();
    root.removeAppender(appender.getName());
    ctx.updateLoggers();
    appender.stop();
  }

  /// Discards the events collected so far
  public void clear() {
    synchronized (appender.events) {
      appender.events.clear();
    }
  }

  /// Snapshot of the collected events
  public List<LogEvent> events() {
    synchronized (appender.events) {
      return new ArrayList<>(appender.events);
    }
  }

  /// Formatted messages of all collected events
  public List<String> messages() {
    return events().stream().map(e -> e.getMessage().getFormattedMessage()).toList();
  }

  /// Formatted messages of events from loggers whose name starts with the prefix
  public List<String> messages(String loggerPrefix) {
    return events().stream()
        .filter(e -> e.getLoggerName().startsWith(loggerPrefix))
        .map(e -> e.getMessage().getFormattedMessage())
        .toList();
  }

  /// Formatted messages of events from the logger used by [ProxyLogger#get(Class)]
  public List<String> messages(Class<?> source) {
    return events().stream()
        .filter(e -> e.getLoggerName().equals(source.getName()))
        .map(e -> e.getMessage().getFormattedMessage())
        .toList();
  }

  /// Events at level `ERROR` or more severe
  public List<LogEvent> errors() {
    return events().stream().filter(e -> e.getLevel().isMoreSpecificThan(Level.ERROR)).toList();
  }

  private static class Appender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();

    Appender() {
      // unique name, so captures of different test instances never replace each other
      super(
          "Log4jCapture-" + System.identityHashCode(new Object()),
          null,
          null,
          true,
          Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      // the event object may be reused by Log4j2, so keep an immutable copy
      LogEvent copy = event.toImmutable();
      synchronized (events) {
        events.add(copy);
      }
    }
  }
}
