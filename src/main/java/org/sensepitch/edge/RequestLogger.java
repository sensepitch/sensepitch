package org.sensepitch.edge;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/// Receives every completed request, for writing the request log or for metrics.
///
/// The request log is written directly to standard out, not through the logging framework,
/// which sends diagnostics to standard error. See [LogTarget] for the rationale.
///
/// @author Jens Wilke
public interface RequestLogger {

  /// Buffer size of [#bufferedStandardOut()]
  int STANDARD_OUT_BUFFER_SIZE = 64 * 1024;

  /// Standard out with a buffer and without auto flush, as the destination of the request log.
  ///
  /// [System#out] flushes on every line, which costs a system call per request. For the request
  /// log, a short delay and losing the last lines on a crash are acceptable, so it is buffered.
  /// The owner must flush periodically and on shutdown, see [Proxy#start()].
  // FIXME: thread safe?
  static PrintStream bufferedStandardOut() {
    return new PrintStream(
        new BufferedOutputStream(
            new FileOutputStream(FileDescriptor.out), STANDARD_OUT_BUFFER_SIZE),
        false,
        StandardCharsets.UTF_8);
  }

  /// This is called for each response generated, also for timeouts. The data of the info object is
  /// only valid during the duration of the call. The call is made in the channel event loop of the
  /// request any heavy processing and I/O needs to be scheduled.
  void logRequest(RequestLogInfo info);
}
