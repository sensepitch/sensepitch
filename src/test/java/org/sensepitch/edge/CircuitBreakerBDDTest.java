package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.annotations.Steps;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// States of the [CircuitBreaker], with a clock of the test. The backoff starts at 1 s, the jitter
/// is off, so a backoff takes half of its nominal value: 500 ms after the first failure, 1 s after
/// the second.
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class CircuitBreakerBDDTest {

  @Steps BreakerSteps steps;

  @Test
  void closed_grantsRequests() {
    steps.given_a_breaker().then_requests_are_granted(3);
  }

  @Test
  void failure_opensUntilBackoffIsOver() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .then_the_state_is(CircuitBreaker.State.OPEN)
        .then_a_request_is_refused()
        .when_time_passes_millis(499)
        .then_a_request_is_refused()
        .when_time_passes_millis(1)
        .then_a_request_is_granted()
        .then_the_state_is(CircuitBreaker.State.HALF_OPEN)
        .then_a_request_is_refused();
  }

  @Test
  void probeSuccess_closes() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_time_passes_millis(500)
        .then_a_request_is_granted()
        .when_a_request_succeeds()
        .then_the_state_is(CircuitBreaker.State.CLOSED)
        .then_requests_are_granted(3);
  }

  @Test
  void probeFailure_doublesTheBackoff() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_time_passes_millis(500)
        .then_a_request_is_granted()
        .when_a_request_fails()
        .then_the_state_is(CircuitBreaker.State.OPEN)
        .when_time_passes_millis(999)
        .then_a_request_is_refused()
        .when_time_passes_millis(1)
        .then_a_request_is_granted();
  }

  @Test
  void failureWhileOpen_isNotCountedAgain() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_a_request_fails()
        .then_the_failures_are(1)
        .when_time_passes_millis(500)
        .then_a_request_is_granted();
  }

  @Test
  void probeWithoutAnswer_isReplacedAfterTheProbeTimeout() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_time_passes_millis(500)
        .then_a_request_is_granted()
        .when_time_passes_millis(BreakerSteps.PROBE_TIMEOUT_MILLIS - 1)
        .then_a_request_is_refused()
        .when_time_passes_millis(1)
        .then_a_request_is_granted();
  }

  @Test
  void longerRetryAfter_wins() {
    steps
        .given_a_breaker()
        .when_a_request_fails_with_retry_after_millis(10_000)
        .when_time_passes_millis(9_999)
        .then_a_request_is_refused()
        .when_time_passes_millis(1)
        .then_a_request_is_granted();
  }

  @Test
  void retryAfterOfARequestInFlight_extendsTheBackoff() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_a_request_fails_with_retry_after_millis(3_000)
        .when_time_passes_millis(2_999)
        .then_a_request_is_refused()
        .when_time_passes_millis(1)
        .then_a_request_is_granted();
  }

  @Test
  void skipBackoff_grantsAProbeAtOnce() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_the_backoff_is_skipped()
        .then_a_request_is_granted()
        .then_the_state_is(CircuitBreaker.State.HALF_OPEN);
  }

  /// The events of an outage, as the metrics count them, and the wall clock times of the trip and
  /// the restart. A failed probe opens the breaker again but is no new trip.
  @Test
  void outageAndRecovery_reportsEventsAndTimes() {
    steps
        .given_a_breaker()
        .then_the_start_time_is_millis(BreakerSteps.START_MILLIS)
        .then_the_trip_and_restart_times_are_millis(0, 0)
        .when_time_passes_millis(100)
        .when_a_request_fails()
        .then_the_events_are("call failed", "error http_503", "closed to open: http_503")
        .then_a_request_is_refused()
        .then_the_events_are("call not_permitted")
        .when_time_passes_millis(500)
        .then_a_request_is_granted()
        .then_a_request_is_refused()
        .then_the_events_are("open to half_open: backoff_over", "call not_permitted")
        .when_a_request_fails()
        .then_the_events_are("call failed", "error http_503", "half_open to open: http_503")
        .when_time_passes_millis(1_000)
        .then_a_request_is_granted()
        .when_a_request_succeeds()
        .then_the_events_are(
            "open to half_open: backoff_over", "call successful", "half_open to closed: recovered")
        .then_the_trip_and_restart_times_are_millis(
            BreakerSteps.START_MILLIS + 100, BreakerSteps.START_MILLIS + 1_600)
        .when_a_request_succeeds()
        .then_the_events_are("call successful");
  }

  /// A failure that retrying cannot fix is counted, the breaker stays closed
  @Test
  void ignoredFailure_isReportedOnly() {
    steps
        .given_a_breaker()
        .when_a_request_fails_ignored()
        .then_the_events_are("call ignored", "error http_400")
        .then_the_state_is(CircuitBreaker.State.CLOSED)
        .then_the_failures_are(0);
  }

  /// Another thread closes the breaker while this one waits for its lock, after it saw the open
  /// state. Reproduced with a clock that closes the breaker when read inside the lock.
  @Test
  void closedWhileWaitingForTheLock_grants() {
    steps
        .given_a_breaker()
        .when_a_request_fails()
        .when_another_thread_closes_the_breaker_during_the_next_request()
        .then_a_request_is_granted()
        .then_the_state_is(CircuitBreaker.State.CLOSED);
  }

  /// Asked for each request on an event loop, so the closed state must not allocate
  @Test
  void closed_doesNotAllocate() {
    CircuitBreaker breaker =
        new CircuitBreaker(
            new Backoff(1_000, 30_000),
            1_000,
            new RequestLogMetrics().circuitBreakers.events("test", "b"));
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    for (int i = 0; i < 20_000; i++) {
      breaker.tryAcquire();
    }
    long before = threads.getCurrentThreadAllocatedBytes();
    boolean granted = true;
    for (int i = 0; i < 10_000; i++) {
      granted &= breaker.tryAcquire();
    }
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    assertThat(granted).isTrue();
    // the measurement itself may allocate a few bytes
    assertThat(allocated).as("bytes allocated by 10k calls").isLessThan(1_000);
  }

  public static class BreakerSteps {

    static final long PROBE_TIMEOUT_MILLIS = 5_000;
    static final long START_MILLIS = 1_700_000_000_000L;

    private long nowNanos = 1_000_000_000L;
    private long nowMillis = START_MILLIS;
    private CircuitBreaker breaker;
    private final List<String> events = new ArrayList<>();

    /// Runs when the breaker reads the clock, i.e. inside its lock
    private Runnable onClockRead = () -> {};

    @Step("Given a breaker with a backoff of 1 s to 30 s, without jitter")
    public BreakerSteps given_a_breaker() {
      breaker =
          new CircuitBreaker(
              new Backoff(1_000, 30_000, bound -> 0),
              PROBE_TIMEOUT_MILLIS,
              new RecordingEvents(),
              () -> {
                onClockRead.run();
                return nowNanos;
              },
              () -> nowMillis);
      return this;
    }

    /// Records the events as text, see [#then_the_events_are(String...)]
    private final class RecordingEvents implements CircuitBreaker.Events {

      @Override
      public void call(CircuitBreaker.Kind kind) {
        events.add("call " + CircuitBreakerMetrics.label(kind));
      }

      @Override
      public void error(String error) {
        events.add("error " + error);
      }

      @Override
      public void transition(CircuitBreaker.State from, CircuitBreaker.State to, String reason) {
        events.add(
            CircuitBreakerMetrics.label(from)
                + " to "
                + CircuitBreakerMetrics.label(to)
                + ": "
                + reason);
      }
    }

    @Step
    public BreakerSteps when_a_request_fails() {
      breaker.onFailure(0, "http_503");
      return this;
    }

    @Step
    public BreakerSteps when_a_request_fails_ignored() {
      breaker.onIgnored("http_400");
      return this;
    }

    @Step
    public BreakerSteps when_a_request_fails_with_retry_after_millis(long millis) {
      breaker.onFailure(millis, "http_429");
      return this;
    }

    @Step
    public BreakerSteps when_a_request_succeeds() {
      breaker.onSuccess();
      return this;
    }

    @Step
    public BreakerSteps when_time_passes_millis(long millis) {
      nowNanos += TimeUnit.MILLISECONDS.toNanos(millis);
      nowMillis += millis;
      return this;
    }

    @Step
    public BreakerSteps when_another_thread_closes_the_breaker_during_the_next_request() {
      onClockRead =
          () -> {
            onClockRead = () -> {};
            // reentrant, the lock is held by this thread
            breaker.onSuccess();
          };
      return this;
    }

    @Step
    public BreakerSteps when_the_backoff_is_skipped() {
      breaker.skipBackoff();
      return this;
    }

    @Step
    public BreakerSteps then_a_request_is_granted() {
      assertThat(breaker.tryAcquire()).as("granted").isTrue();
      return this;
    }

    @Step
    public BreakerSteps then_a_request_is_refused() {
      assertThat(breaker.tryAcquire()).as("granted").isFalse();
      return this;
    }

    @Step
    public BreakerSteps then_requests_are_granted(int count) {
      for (int i = 0; i < count; i++) {
        then_a_request_is_granted();
      }
      return this;
    }

    @Step
    public BreakerSteps then_the_state_is(CircuitBreaker.State state) {
      assertThat(breaker.state()).isEqualTo(state);
      return this;
    }

    /// The events since the last check
    @Step
    public BreakerSteps then_the_events_are(String... expected) {
      assertThat(events).containsExactly(expected);
      events.clear();
      return this;
    }

    @Step
    public BreakerSteps then_the_start_time_is_millis(long millis) {
      assertThat(breaker.startMillis()).isEqualTo(millis);
      return this;
    }

    @Step
    public BreakerSteps then_the_trip_and_restart_times_are_millis(long trip, long restart) {
      assertThat(breaker.lastTripMillis()).as("trip").isEqualTo(trip);
      assertThat(breaker.lastRestartMillis()).as("restart").isEqualTo(restart);
      return this;
    }

    @Step
    public BreakerSteps then_the_failures_are(int failures) {
      assertThat(breaker.failures()).isEqualTo(failures);
      return this;
    }
  }
}
