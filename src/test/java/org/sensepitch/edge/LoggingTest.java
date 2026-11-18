package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// Netty and JUL logs end up in Log4j2.
///
/// @author Jens Wilke
// alone: captures what arrives in Log4j2, which is one for the JVM
@Isolated
public class LoggingTest {

  @RegisterExtension LogCapture logs = new LogCapture();
  @RegisterExtension Log4jCapture log4j = new Log4jCapture();

  @Test
  public void nettyLogger() {
    var logger = InternalLoggerFactory.getInstance("in");
    logger.error("error message to netty logger");
    logger.warn("warn message to netty logger");
    logger.info("info message to netty logger");
    assertThat(log4j.messages("in"))
        .containsExactly(
            "error message to netty logger",
            "warn message to netty logger",
            "info message to netty logger");
  }

  @Test
  public void julLogger() {
    java.util.logging.Logger.getLogger("jul").warning("warn message to JUL");
    assertThat(log4j.messages("jul")).containsExactly("warn message to JUL");
  }
}
