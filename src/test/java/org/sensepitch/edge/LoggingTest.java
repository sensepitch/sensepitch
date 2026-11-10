package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Netty and JUL logs end up in Log4j2 and are capturable.
///
/// @author Jens Wilke
public class LoggingTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void nettyLogger() {
    var logger = InternalLoggerFactory.getInstance("in");
    logger.error("error message to netty logger");
    logger.warn("warn message to netty logger");
    logger.info("info message to netty logger");
    assertThat(logs.messages("in"))
        .containsExactly(
            "error message to netty logger",
            "warn message to netty logger",
            "info message to netty logger");
  }

  @Test
  public void julLogger() {
    java.util.logging.Logger.getLogger("jul").warning("warn message to JUL");
    assertThat(logs.messages("jul")).containsExactly("warn message to JUL");
  }
}
