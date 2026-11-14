package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.System.Logger.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configurator;
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

  static final String NAME = LogTargetTest.class.getName();

  /// Tracing is available by default, the level decides. Tests run the proxy's loggers at TRACE,
  /// see `log4j2-test.xml`. The trace calls are run for coverage, their output is not checked.
  @Test
  public void defaultLoggerTraces() {
    ProxyLogger log = ProxyLogger.get(LogTargetTest.class);
    assertThat(log).isInstanceOf(ProxyLogger.WithTracing.class);
    assertThat(log.isTraceEnabled()).isTrue();
    EmbeddedChannel down = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    EmbeddedChannel up = new EmbeddedChannel();
    log.trace("plain");
    log.trace(down, "down");
    log.trace(down, up, "both");
    log.traceChannelRead(down.pipeline().firstContext(), "a message");
  }

  /// The kill switch `SENSEPITCH_EDGE_DISABLE_TRACE` selects a logger without tracing, everything
  /// else is logged.
  @Test
  public void errorOnlyNeverTraces() {
    ProxyLogger log = ProxyLogger.create(NAME, true);
    assertThat(log).isInstanceOf(ProxyLogger.ErrorOnly.class);
    assertThat(log.isTraceEnabled()).isFalse();
    EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    log.trace("plain");
    log.trace(channel, "down");
    log.trace(channel, channel, "both");
    log.traceChannelRead(channel.pipeline().firstContext(), "a message");
    log.error("still logged");
    assertThat(logs.messages(LogTargetTest.class)).containsExactly("still logged");
  }

  @Test
  public void levelDecidesForWithTracing() {
    ProxyLogger log = ProxyLogger.create(NAME, false);
    Configurator.setLevel(NAME, org.apache.logging.log4j.Level.INFO);
    try {
      assertThat(log.isTraceEnabled()).isFalse();
      log.trace("not visible at INFO");
    } finally {
      Configurator.setLevel(NAME, org.apache.logging.log4j.Level.TRACE);
    }
  }

  @Test
  public void errorsWarningsAndInfo() {
    ProxyLogger log = ProxyLogger.get(LogTargetTest.class);
    EmbeddedChannel down = new EmbeddedChannel();
    EmbeddedChannel up = new EmbeddedChannel();
    String d = down.id().asShortText();
    String u = up.id().asShortText();
    Throwable cause = new Throwable("expected");
    log.error(down, cause);
    log.error(down, "with message");
    log.error(down, "with cause", cause);
    log.error(down, up, "both channels", cause);
    log.error("plain", cause);
    log.warn("a warning");
    log.info("an info");
    assertThat(logs.messages(LogTargetTest.class))
        .containsExactly(
            d,
            d + " with message",
            d + " with cause",
            d + ">" + u + " both channels",
            "plain",
            "a warning",
            "an info");
    assertThat(log.channelId(null)).isEqualTo("null");
  }

  @Test
  public void levelMapping() {
    for (Level level : Level.values()) {
      assertThat(LogTarget.Log4jOutput.toLog4j(level).name())
          .isEqualTo(level == Level.WARNING ? "WARN" : level.name());
    }
    assertThat(LogTarget.Log4jOutput.toLog4j(null)).isEqualTo(org.apache.logging.log4j.Level.INFO);
  }
}
