package org.sensepitch.edge;

import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class LoggingTest {

  @Test
  public void nettyLogger() {
    var logger = InternalLoggerFactory.getInstance("in");
    logger.error("error message to netty logger");
    logger.warn("warn message to netty logger");
    logger.info("info message to netty logger");
  }
}
