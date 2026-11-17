package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// The shutdown order of [Main]: services first, Log4j2 last. [ShadedJarIT] checks the same with
/// a real `SIGTERM` against the deployed JAR.
@Timeout(10)
class MainShutdownTest {

  /// Log4j2's own hook would run concurrently with ours and stop logging while the proxy drains.
  @ParameterizedTest
  @ValueSource(strings = {"log4j2.xml", "log4j2-trace.xml"})
  void bundledConfigurationDisablesLog4jShutdownHook(String resource) throws Exception {
    try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
      var root =
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
      assertThat(root.getAttribute("shutdownHook")).isEqualTo("disable");
    }
  }

  @Test
  void servicesStopBeforeLoggingAndOnlyOnce() {
    List<String> events = new ArrayList<>();
    var shutdown = new Main.Shutdown(() -> events.add("services"), () -> events.add("logging"));
    shutdown.run();
    shutdown.run();
    assertThat(events).containsExactly("services", "logging");
  }

  @Test
  void serviceFailureStillStopsLoggingOnce() {
    List<String> events = new ArrayList<>();
    var shutdown =
        new Main.Shutdown(
            () -> {
              events.add("services");
              throw new IllegalStateException("cleanup failed");
            },
            () -> events.add("logging"));
    assertThatThrownBy(shutdown::run).hasMessage("cleanup failed");
    shutdown.run();
    assertThat(events).containsExactly("services", "logging");
  }

  /// The main thread and the JVM shutdown hook both call it; the second must not return before
  /// logging is stopped, or the JVM could exit with the first still running.
  @Test
  void concurrentCallerWaitsForLoggingShutdown() throws Exception {
    CountDownLatch loggingStarted = new CountDownLatch(1);
    CountDownLatch releaseLogging = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    var shutdown =
        new Main.Shutdown(
            () -> {},
            () -> {
              loggingStarted.countDown();
              try {
                assertThat(releaseLogging.await(5, TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(shutdown);
      try {
        assertThat(loggingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        var second =
            executor.submit(
                () -> {
                  secondStarted.countDown();
                  shutdown.run();
                });
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second).isNotDone();
        releaseLogging.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
      } finally {
        releaseLogging.countDown();
      }
    }
  }
}
