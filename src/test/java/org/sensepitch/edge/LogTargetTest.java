package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class LogTargetTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void errorIsForwardedToLog4jWithThrowable() {
    Throwable error = new Throwable("expected");
    LogTarget.of("org.sensepitch.LoggingSource")
        .log(LogInfo.builder().level(Level.ERROR).message("message text").error(error).build());
    assertThat(logs.errors()).hasSize(1);
    LogEvent event = logs.errors().getFirst();
    assertThat(event.getLoggerName()).isEqualTo("org.sensepitch.LoggingSource");
    assertThat(event.getMessage().getFormattedMessage()).isEqualTo("message text");
    assertThat(event.getThrown()).isSameAs(error);
  }

  @Test
  public void proxyLoggerUsesClassNameAndKeepsCause() {
    Throwable error = new Throwable("expected");
    ProxyLogger.get(LogTargetTest.class).error("failed", error);
    assertThat(logs.messages(LogTargetTest.class)).containsExactly("failed");
    assertThat(logs.errors().getFirst().getThrown()).isSameAs(error);
  }

  @Test
  public void traceIsOffWithoutEnvironmentSwitch() {
    if (!ProxyLogger.TRACE_POSSIBLE) {
      assertThat(ProxyLogger.get(LogTargetTest.class).isTraceEnabled()).isFalse();
    }
    ProxyLogger.get(LogTargetTest.class).trace("not visible at INFO");
    assertThat(logs.messages(LogTargetTest.class)).isEmpty();
  }
}
