package org.sensepitch.edge;

/// Passes each request to all loggers, and closes all of them.
///
/// @author Jens Wilke
public class DistributingRequestLogger implements RequestLogger {

  private final RequestLogger[] requestLogger;

  public DistributingRequestLogger(RequestLogger... requestLogger) {
    this.requestLogger = requestLogger;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    for (RequestLogger requestLogger : requestLogger) {
      requestLogger.logRequest(info);
    }
  }

  @Override
  public void close() {
    for (RequestLogger requestLogger : requestLogger) {
      requestLogger.close();
    }
  }
}
