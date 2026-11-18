package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Reloads under concurrent logging. It adds no coverage to [ReloadableRequestLoggerTest], which
/// is the place for new scenarios and provides the OTLP server and helpers as a fixture.
///
/// @author Jens Wilke
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReloadableRequestLoggerIT {

  final ReloadableRequestLoggerTest fixture = new ReloadableRequestLoggerTest();

  @BeforeEach
  void startServer() throws Exception {
    fixture.startServer();
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  /// Threads keep logging while reloads replace the destination again and again: every record
  /// arrives exactly once, none is dropped at a switch.
  ///
  /// The logging threads are single thread executors passed to the logger, like the event loops:
  /// a reload runs the end marker of a replaced destination on each of them. A plain thread the
  /// logger does not know may log into a destination for the first time after it finished, that
  /// record is refused and counted as dropped.
  @Test
  void noRecordIsLostWhileReloading() throws Exception {
    int threads = 4;
    int perThread = 300;
    int minReloads = 20;
    List<ExecutorService> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      workers.add(Executors.newSingleThreadExecutor());
    }
    try {
      ReloadableRequestLogger logger =
          RequestLoggerFactory.create(
              ReloadableRequestLoggerTest.config(fixture.otlp("vl", 1)),
              fixture.stdout,
              fixture.metrics,
              workers);
      fixture.logger = logger;
      List<Future<?>> lastRecords = new ArrayList<>();
      for (ExecutorService worker : workers) {
        Future<?> last = null;
        for (int i = 0; i < perThread; i++) {
          last = worker.submit(fixture::logOneRequest);
        }
        lastRecords.add(last);
      }
      int[] reloads = {0};
      // with few records the workers may finish early, keep reloading to a minimum count
      await(
          "logging workers finished after the minimum reloads",
          () -> {
            if (lastRecords.stream().allMatch(Future::isDone) && reloads[0] >= minReloads) {
              return true;
            }
            fixture.reload(
                ReloadableRequestLoggerTest.config(fixture.otlp("vl", 1 + reloads[0] % 2)));
            reloads[0]++;
            Thread.sleep(1);
            return false;
          });
      // the end markers of the workers, as an event loop writes them when it terminates
      for (ExecutorService worker : workers) {
        worker.submit(logger::endThread).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      // right after the reloads, replaced destinations may still be handing over
      logger.close();
      int got = fixture.awaitReceived(threads * perThread);
      assertThat(fixture.dropped("vl")).isZero();
      assertThat(got).as("every record exactly once").isEqualTo(threads * perThread);
      assertThat(reloads[0]).as("the destination was replaced often").isGreaterThan(10);
    } finally {
      workers.forEach(ExecutorService::shutdownNow);
    }
  }
}
