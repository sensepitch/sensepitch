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

/// Collects Log4j2 events in memory for the duration of a test, so tests can assert on log
/// output. This covers [ProxyLogger], Netty and JUL, since all of them end up in Log4j2.
///
/// Register as a field:
///
/// ```java
/// @RegisterExtension LogCapture logs = new LogCapture();
/// ```
///
/// The in-memory appender is attached to the root logger before each test and removed afterwards.
/// By default it captures events at `INFO` and above. The proxy's own loggers run at `TRACE` in
/// tests (see `log4j2-test.xml`), use [#LogCapture(Level)] to capture trace events as well.
///
/// @author Jens Wilke
public class LogCapture implements BeforeEachCallback, AfterEachCallback {

  private final Appender appender = new Appender();
  private final Level minLevel;

  /// Captures events at `INFO` and above
  public LogCapture() {
    this(Level.INFO);
  }

  /// @param minLevel least severe level captured, e.g. `TRACE` to capture everything
  public LogCapture(Level minLevel) {
    this.minLevel = minLevel;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    clear();
    appender.start();
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    config.getRootLogger().addAppender(appender, minLevel, null);
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
      // unique name, so a test can register more than one capture, e.g. with different levels
      super(
          "LogCapture-" + System.identityHashCode(new Object()),
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
