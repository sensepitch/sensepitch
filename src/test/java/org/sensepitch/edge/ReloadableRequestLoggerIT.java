package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
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

/// Real server reload checks, including concurrent logging. These repeat the unit coverage of
/// [ReloadableRequestLoggerTest], the place for new scenarios, which provides the server and
/// helpers.
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

  /// A removed destination whose log server does not come back ends once its records are older
  /// than the maximum age, they are counted as dropped
  @Test
  void finishedDestinationEndsWhenItsRecordsAgedOut() throws Exception {
    fixture.status = 503;
    RequestLogDestinationConfig shortAge = fixture.otlp("vl", 1);
    shortAge =
        shortAge.toBuilder()
            .otlp(shortAge.otlp().toBuilder().maximumUnsentLogAgeMillis(300).build())
            .build();
    fixture.logger =
        RequestLoggerFactory.create(
            ReloadableRequestLoggerTest.config(shortAge), fixture.stdout, fixture.metrics);
    for (int i = 0; i < 10; i++) {
      fixture.logOneRequest();
    }
    fixture.awaitAttempts(1);
    fixture.reload(ReloadableRequestLoggerTest.config(ReloadableRequestLoggerTest.stdout(false)));
    await("removed destination ended", () -> !fixture.queuedDestinations().contains("vl"));
    assertThat(fixture.queuedDestinations()).as("the destination ended").doesNotContain("vl");
    assertThat(fixture.dropped("vl")).isEqualTo(10);
  }

  /// Switching the request log off ends its destinations, switching it on creates them again
  @Test
  void disableAndEnableByReload() throws Exception {
    fixture.logger =
        RequestLoggerFactory.create(
            ReloadableRequestLoggerTest.config(fixture.otlp("vl", OUT_OF_REACH_MILLIS)),
            fixture.stdout,
            fixture.metrics);
    fixture.logOneRequest();
    fixture.reload(RequestLogConfig.builder().disabled(true).build());
    assertThat(fixture.logger.destinations()).isEmpty();
    assertThat(fixture.awaitReceived(1)).as("sent after the end marker").isOne();
    fixture.logOneRequest();
    fixture.reload(ReloadableRequestLoggerTest.config(fixture.otlp("vl", OUT_OF_REACH_MILLIS)));
    fixture.logOneRequest();
    fixture.logger.close();
    assertThat(fixture.awaitReceived(1)).as("only the record after enabling").isOne();
  }

  /// Removing a destination during an outage does not wait for the log server, the removed
  /// destination sends its records once the server is back
  @Test
  void removedDestinationDoesNotBlockTheReload() throws Exception {
    fixture.status = 503;
    fixture.logger =
        RequestLoggerFactory.create(
            ReloadableRequestLoggerTest.config(fixture.otlp("vl", 1)),
            fixture.stdout,
            fixture.metrics);
    for (int i = 0; i < 20; i++) {
      fixture.logOneRequest();
    }
    fixture.awaitAttempts(1);
    long start = System.nanoTime();
    fixture.reload(ReloadableRequestLoggerTest.config(ReloadableRequestLoggerTest.stdout(false)));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .as("the reload does not wait for the log server")
        .isLessThan(500);
    fixture.status = 200;
    assertThat(fixture.awaitReceived(20)).isEqualTo(20);
    assertThat(fixture.dropped("vl")).isZero();
  }

  /// The log server is unavailable while a reload changes the destination: the replaced
  /// destination keeps its buffered records and sends them once the server is back. The reload
  /// does not wait.
  @Test
  void replacedDestinationSendsItsBacklog() throws Exception {
    fixture.status = 503;
    fixture.logger =
        RequestLoggerFactory.create(
            ReloadableRequestLoggerTest.config(fixture.otlp("vl", 1)),
            fixture.stdout,
            fixture.metrics);
    for (int i = 0; i < 50; i++) {
      fixture.logOneRequest();
    }
    fixture.awaitAttempts(1);
    long start = System.nanoTime();
    fixture.reload(ReloadableRequestLoggerTest.config(fixture.otlp("vl", 2)));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .as("the reload does not wait for the log server")
        .isLessThan(500);
    fixture.status = 200;
    assertThat(fixture.awaitReceived(50)).isEqualTo(50);
    assertThat(fixture.dropped("vl")).isZero();
  }
}
