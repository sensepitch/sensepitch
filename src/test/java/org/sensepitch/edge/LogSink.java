package org.sensepitch.edge;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/// The [LogTarget] of all [ProxyLogger]s in tests: keeps the records in memory, per logging
/// thread, for [LogCapture]. Nothing is formatted and Log4j2 is not involved, a record is one
/// thread local lookup and a list add.
///
/// Storing per thread is what makes tests independent of each other: a test sees what its own
/// thread logged, whatever other tests log at the same time. The same design is used by
/// slf4j-test (`TestLogger.getLoggingEvents()` and `getAllLoggingEvents()`). A capture attached
/// to the logging framework instead, e.g. the `ListAppender` of Log4j2, is shared by the whole
/// JVM.
///
/// Trace is enabled, so tests run the trace code, but trace and debug records are dropped: trace
/// output is a debugging aid and no acceptance criterion.
///
/// For debugging, add an appender to the root logger in `log4j2-test.xml`: then every record is
/// passed on to Log4j2 as well.
///
/// @author Jens Wilke
final class LogSink implements LogTarget {

  /// A captured record with the name of its logger
  record Entry(String source, LogInfo info) {}

  /// Records of one thread. Synchronized, a capture of all threads reads it from another thread.
  private static final class Buffer {
    private final List<Entry> entries = new ArrayList<>();

    synchronized void add(Entry entry) {
      entries.add(entry);
    }

    synchronized void clear() {
      entries.clear();
    }

    synchronized void copyTo(List<Entry> target) {
      target.addAll(entries);
    }
  }

  /// Buffers of all threads that logged, for captures of all threads. A buffer outlives its
  /// thread until the next [#clearAllThreads()]: short lived threads log as well, and what they
  /// logged must not disappear with them.
  private static final Map<Thread, Buffer> BUFFERS = new ConcurrentHashMap<>();

  /// Fast path to the buffer of the logging thread. Pool threads may erase their thread locals
  /// between tasks, e.g. the workers of the common pool, so the buffer is looked up again and
  /// not replaced, otherwise the records before the erasure were lost.
  private static final ThreadLocal<Buffer> BUFFER =
      ThreadLocal.withInitial(
          () -> BUFFERS.computeIfAbsent(Thread.currentThread(), thread -> new Buffer()));

  /// Set when a developer added an appender to the test configuration, see the class doc
  private static final boolean FORWARD =
      !((LoggerContext) LogManager.getContext(false))
          .getConfiguration()
          .getRootLogger()
          .getAppenders()
          .isEmpty();

  private static volatile boolean installed;

  private final String source;
  private final LogTarget forward;

  private LogSink(String source) {
    this.source = source;
    this.forward = FORWARD ? Factory.log4j(source) : null;
  }

  @Override
  public boolean isEnabled(Level level) {
    return true;
  }

  @Override
  public void log(LogInfo info) {
    if (forward != null) {
      forward.log(info);
    }
    if (info.level().getSeverity() >= Level.INFO.getSeverity()) {
      BUFFER.get().add(new Entry(source, info));
    }
  }

  // for LogCapture

  /// Whether the loggers write to this sink. If not, a capture would see nothing and assertions
  /// that expect no message would pass for the wrong reason.
  static boolean installed() {
    return installed;
  }

  static void clearCurrentThread() {
    BUFFER.get().clear();
  }

  static void clearAllThreads() {
    BUFFERS.forEach(
        (thread, buffer) -> {
          buffer.clear();
          if (!thread.isAlive()) {
            BUFFERS.remove(thread);
          }
        });
  }

  static List<Entry> entriesOfCurrentThread() {
    List<Entry> entries = new ArrayList<>();
    BUFFER.get().copyTo(entries);
    return entries;
  }

  /// Records of all threads, in the order of each thread, threads one after the other
  static List<Entry> entriesOfAllThreads() {
    List<Entry> entries = new ArrayList<>();
    for (Buffer buffer : BUFFERS.values()) {
      buffer.copyTo(entries);
    }
    return entries;
  }

  /// Makes this sink the target of all loggers before the first test class is loaded. Registered
  /// in `META-INF/services`, so it also applies to tests started from an IDE.
  public static final class Installer implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
      Factory.replace(LogSink::new);
      installed = true;
    }
  }
}
