package org.sensepitch.edge;

/// Destination of the request log, one rendered record per line.
///
/// Implementations must be thread safe, since event loops call [#writeLine(String)]
/// concurrently. The call is made in the event loop, so it must not block, see
/// [AsyncRequestLogWriter] for the production implementation.
///
/// @author Jens Wilke
public interface RequestLogOutput extends AutoCloseable {

  /// Writes one record, the line separator is appended by the output
  void writeLine(String line);

  /// Writes out pending records and releases resources. Default does nothing.
  @Override
  default void close() {}
}
