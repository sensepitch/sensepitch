package org.sensepitch.edge;

import java.lang.System.Logger.Level;
import java.util.List;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Isolated;

/// The messages the [ProxyLogger]s logged during a test, so tests can assert on log output.
///
/// Register as a field:
///
/// ```java
/// @RegisterExtension LogCapture logs = new LogCapture();
/// ```
///
/// In tests the loggers write to the [LogSink], which keeps the records per logging thread. A
/// capture shows the records of the test's own thread and forgets the earlier ones before each
/// test. Tests running in other threads at the same time do not show up.
///
/// Code under test that logs from its own threads, e.g. event loops of a started [Proxy] or the
/// sender of a request log destination, needs [#ofAllThreads()]. Such a capture also sees what
/// other tests log, so its test class must be `@Isolated`: it then runs while no other class
/// does.
///
/// Only `INFO` and above is captured, see [LogSink]. Messages of Netty, JUL and other libraries
/// do not pass the proxy's loggers, [Log4jCapture] sees them.
///
/// @author Jens Wilke
public class LogCapture implements BeforeEachCallback {

  private final boolean allThreads;

  /// Captures what the thread of the test logs
  public LogCapture() {
    this(false);
  }

  private LogCapture(boolean allThreads) {
    this.allThreads = allThreads;
  }

  /// Captures what any thread logs, see the class doc for the consequence. The test class must
  /// be `@Isolated`, which is checked.
  public static LogCapture ofAllThreads() {
    return new LogCapture(true);
  }

  /// A captured record
  public record Entry(String source, LogInfo info) {

    /// The message as it is logged, without the throwable
    public String message() {
      return LogTarget.format(info);
    }

    public Throwable error() {
      return info.error();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (!LogSink.installed()) {
      throw new IllegalStateException(
          "LogSink is not installed, see META-INF/services in the test resources");
    }
    if (allThreads) {
      requireIsolated(context, "LogCapture.ofAllThreads()");
    }
    clear();
  }

  /// Fails a test class that reads JVM wide state without `@Isolated`: run concurrently with
  /// other classes, it would see their log lines.
  static void requireIsolated(ExtensionContext context, String what) {
    if (!context.getRequiredTestClass().isAnnotationPresent(Isolated.class)) {
      throw new IllegalStateException(
          what
              + " sees the log of other tests, annotate "
              + context.getRequiredTestClass().getSimpleName()
              + " with @Isolated");
    }
  }

  /// Discards the records collected so far
  public void clear() {
    if (allThreads) {
      LogSink.clearAllThreads();
    } else {
      LogSink.clearCurrentThread();
    }
  }

  /// Snapshot of the collected records
  public List<Entry> entries() {
    List<LogSink.Entry> entries =
        allThreads ? LogSink.entriesOfAllThreads() : LogSink.entriesOfCurrentThread();
    return entries.stream().map(e -> new Entry(e.source(), e.info())).toList();
  }

  /// Messages of all collected records
  public List<String> messages() {
    return entries().stream().map(Entry::message).toList();
  }

  /// Messages from loggers whose name starts with the prefix
  public List<String> messages(String loggerPrefix) {
    return entries().stream()
        .filter(e -> e.source().startsWith(loggerPrefix))
        .map(Entry::message)
        .toList();
  }

  /// Messages from the logger used by [ProxyLogger#get(Class)]
  public List<String> messages(Class<?> source) {
    return entries().stream()
        .filter(e -> e.source().equals(source.getName()))
        .map(Entry::message)
        .toList();
  }

  /// Records at level `ERROR`
  public List<Entry> errors() {
    return entries().stream().filter(e -> e.info().level() == Level.ERROR).toList();
  }
}
