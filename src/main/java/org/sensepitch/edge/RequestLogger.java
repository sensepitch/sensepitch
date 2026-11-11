package org.sensepitch.edge;

/// Receives every completed request, for writing the request log or for metrics.
///
/// The request log is written directly to standard out, not through the logging framework,
/// which sends diagnostics to standard error. See [LogTarget] for the rationale and
/// [AsyncRequestLogWriter] for how it is written without blocking the event loops.
///
/// @author Jens Wilke
public interface RequestLogger extends AutoCloseable {

  /// This is called for each response generated, also for timeouts. The data of the info object is
  /// only valid during the duration of the call. The call is made in the channel event loop of the
  /// request any heavy processing and I/O needs to be scheduled.
  void logRequest(RequestLogInfo info);

  /// Writes out pending records and releases resources, e.g. the sender thread of an OTLP
  /// destination. Can be called more than once. Default does nothing.
  @Override
  default void close() {}
}
