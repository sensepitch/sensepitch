package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public interface RequestLogger {

  /**
   * This is called for each response generated, also for timeouts. The data of the info object is
   * only valid during the duration of the call. The call is made in the channel event loop of the
   * request any heavy processing and I/O needs to be scheduled.
   */
  void logRequest(RequestLogInfo info);
}
