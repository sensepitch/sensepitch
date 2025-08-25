package org.sensepitch.edge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Jens Wilke
 */
public class ReportErrorOnce {

  private final Map<String, Object> seen = new ConcurrentHashMap<>();
  private ProxyLogger logger;

  public ReportErrorOnce(ProxyLogger logger) {
    this.logger = logger;
  }

  public void report(String msg) {
    Object obj = seen.put(msg, this);
    if (obj == null) {
      logger.error(msg);
    }
  }
}
