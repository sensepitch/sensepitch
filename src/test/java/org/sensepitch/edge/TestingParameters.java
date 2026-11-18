package org.sensepitch.edge;

import com.sun.net.httpserver.HttpServer;
import java.time.Duration;

/// Shared upper bound for waiting on another thread or process in tests.
final class TestingParameters {

  static final int TIMEOUT_SECONDS = 10;
  static final Duration TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);

  /// For a timer the test relies on never firing (flush interval, age limit): far beyond any
  /// wait, so a scheduler delay of up to [#TIMEOUT] cannot reach it.
  static final Duration OUT_OF_REACH = TIMEOUT.multipliedBy(10);

  /// [#OUT_OF_REACH] for the `int` millisecond fields of the configuration.
  static final int OUT_OF_REACH_MILLIS = (int) OUT_OF_REACH.toMillis();

  private TestingParameters() {}

  /// Bounds asynchronous completion waits and identifies the missing event on failure.
  static void await(String description, Condition condition) throws Exception {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.test()) {
      if (System.nanoTime() - deadline >= 0) {
        throw new AssertionError(
            description + " not reached within " + TIMEOUT_SECONDS + " seconds");
      }
      Thread.sleep(5);
    }
  }

  /// Lets a test server finish an exchange before closing its connection. An idle server returns
  /// at once; a generous grace period tolerates scheduling pauses on loaded machines.
  static void stop(HttpServer server) {
    server.stop(TIMEOUT_SECONDS);
  }

  /// Allows completion probes that read files or sockets to report checked failures.
  @FunctionalInterface
  interface Condition {
    boolean test() throws Exception;
  }
}
