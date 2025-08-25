package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
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
}
