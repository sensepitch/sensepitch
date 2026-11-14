package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.internal.logging.InternalLoggerFactory;
import java.io.IOException;
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

  /// Connection errors name the remote address, so a misbehaving client or upstream can be found.
  @Test
  public void connectionErrorsContainRemoteAddress() {
    ProxyLogger log = ProxyLogger.get(LoggingTest.class);
    log.downstreamError(
        TestChannels.channelFrom("192.0.2.7"), "reset", new IOException("Connection reset"));
    log.upstreamError(
        TestChannels.channelFrom("198.51.100.1"), "refused", new IOException("Connection refused"));
    assertThat(logs.messages(LoggingTest.class))
        .anySatisfy(m -> assertThat(m).contains("DOWNSTREAM ERROR reset 192.0.2.7"))
        .anySatisfy(m -> assertThat(m).contains("UPSTREAM ERROR refused 198.51.100.1"));
  }
}
