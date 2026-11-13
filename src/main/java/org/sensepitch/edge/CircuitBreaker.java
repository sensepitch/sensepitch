package org.sensepitch.edge;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/// Stops requests to a remote that failed, and lets a single probe through after a backoff.
///
/// - `CLOSED`: requests pass, see [#tryAcquire()].
/// - `OPEN`: after a failure, see [#onFailure(long, String)], requests are refused until the backoff is
///   over. A failure of a request that was still in flight when the breaker opened does not
///   count again.
/// - `HALF_OPEN`: once the backoff is over, [#tryAcquire()] lets exactly one request through as
///   a probe. Its success closes the breaker, its failure opens it again with a doubled backoff.
///   A probe that reports nothing within the probe timeout lets the next one through, so the
///   breaker cannot get stuck.
///
/// The breaker is passive: it has no thread or timer, a caller that wants to retry by itself
/// schedules for [#retryAtNanos()]. It is used for posting request logs, see [HttpBatchSender],
/// and fits the connections to an upstream as well.
///
/// [#tryAcquire()] in the closed state reads one volatile field, it neither locks nor allocates,
/// so it can be called for each request on an event loop. State changes lock this object.
///
/// Every outcome, refusal and state change is reported to the [Events], e.g. the metrics, see
/// [CircuitBreakerMetrics]. The breaker keeps the wall clock times of its start, its last trip and
/// its last restart for them.
///
/// @author Jens Wilke
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  /// What happened to a call, the `kind` of the metrics, named as in resilience4j
  public enum Kind {
    /// succeeded, see [#onSuccess()]
    SUCCESSFUL,
    /// failed and counted by the breaker, see [#onFailure(long, String)]
    FAILED,
    /// failed in a way the breaker does not count, see [#onIgnored(String)]
    IGNORED,
    /// refused by the breaker, see [#tryAcquire()]
    NOT_PERMITTED
  }

  /// Receives what the breaker does, mostly with the lock of the breaker held, so an
  /// implementation must not call back into the breaker.
  public interface Events {

    /// A call ended or was refused
    void call(Kind kind);

    /// A call failed, as [Kind#FAILED] or [Kind#IGNORED]
    ///
    /// @param error a short code, e.g. `http_503` or `timeout`, never a message
    void error(String error);

    /// The state changed
    ///
    /// @param reason the error code for a change to `OPEN`, otherwise `backoff_over` or
    ///   `recovered`
    void transition(State from, State to, String reason);
  }

  static final String BACKOFF_OVER = "backoff_over";
  static final String RECOVERED = "recovered";

  private final Backoff backoff;
  private final long probeTimeoutNanos;
  private final LongSupplier nanoClock;
  private final LongSupplier wallClock;
  private final long startMillis;

  private final Events events;

  private volatile State state = State.CLOSED;

  /// Wall clock time of the last change from closed to open, `0` if none
  private volatile long lastTripMillis;

  /// Wall clock time of the last close after a trip, `0` if none
  private volatile long lastRestartMillis;

  // guarded by this
  private int failures;
  private long retryAtNanos;
  private long probeSinceNanos;

  /// @param probeTimeoutMillis time after which a probe that reported nothing is replaced, at
  ///   least the request timeout
  /// @param events receives what the breaker does, e.g. [CircuitBreakerMetrics#events(String, String)]
  public CircuitBreaker(Backoff backoff, long probeTimeoutMillis, Events events) {
    this(backoff, probeTimeoutMillis, events, System::nanoTime, System::currentTimeMillis);
  }

  /// With clocks, for tests
  CircuitBreaker(
      Backoff backoff,
      long probeTimeoutMillis,
      Events events,
      LongSupplier nanoClock,
      LongSupplier wallClock) {
    this.backoff = backoff;
    this.events = events;
    this.probeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(probeTimeoutMillis);
    this.nanoClock = nanoClock;
    this.wallClock = wallClock;
    this.startMillis = wallClock.getAsLong();
  }

  /// Asks for permission to send a request.
  ///
  /// @return `true` if the request may be sent; in the half open state it is the probe and must
  ///   be followed by [#onSuccess()] or [#onFailure(long, String)]. A refusal is reported as
  ///   [Kind#NOT_PERMITTED].
  public boolean tryAcquire() {
    return state == State.CLOSED || tryAcquireSlow();
  }

  private synchronized boolean tryAcquireSlow() {
    long now = nanoClock.getAsLong();
    switch (state) {
      case CLOSED -> {
        // closed by another thread between the volatile read and taking the lock
        return true;
      }
      case OPEN -> {
        if (now - retryAtNanos < 0) {
          events.call(Kind.NOT_PERMITTED);
          return false;
        }
        state = State.HALF_OPEN;
        events.transition(State.OPEN, State.HALF_OPEN, BACKOFF_OVER);
        probeSinceNanos = now;
        return true;
      }
      default -> {
        if (now - probeSinceNanos < probeTimeoutNanos) {
          events.call(Kind.NOT_PERMITTED);
          return false;
        }
        probeSinceNanos = now;
        return true;
      }
    }
  }

  /// A request succeeded, the breaker closes.
  ///
  /// @return the state before, not `CLOSED` means the remote recovered
  public synchronized State onSuccess() {
    events.call(Kind.SUCCESSFUL);
    State before = state;
    if (before != State.CLOSED) {
      state = State.CLOSED;
      failures = 0;
      lastRestartMillis = wallClock.getAsLong();
      events.transition(before, State.CLOSED, RECOVERED);
    }
    return before;
  }

  /// A request failed in a way a retry cannot fix, e.g. a status 400. The breaker stays as it is,
  /// the failure is reported as [Kind#IGNORED].
  ///
  /// @param error short code of the error, see [Events#error(String)]
  public void onIgnored(String error) {
    events.call(Kind.IGNORED);
    events.error(error);
  }

  /// A request failed in a way that a later attempt may fix, e.g. a connection error or a status
  /// 503. The breaker opens for the backoff, or longer if the server requested it.
  ///
  /// @param retryAfterMillis delay the server requested via `Retry-After`, `0` if none
  /// @param error short code of the error, see [Events#error(String)], also the reason of the
  ///   transition to `OPEN`
  /// @return the state before: `CLOSED` means an outage starts, `OPEN` means the failure was
  ///   counted already, the caller does not need to schedule another retry
  public synchronized State onFailure(long retryAfterMillis, String error) {
    events.call(Kind.FAILED);
    events.error(error);
    State before = state;
    long now = nanoClock.getAsLong();
    if (before == State.OPEN) {
      // a request in flight when the breaker opened, only a longer requested delay matters
      retryAtNanos = Math.max(retryAtNanos, now + TimeUnit.MILLISECONDS.toNanos(retryAfterMillis));
      return before;
    }
    long delayMillis = backoff.delayMillis(failures++, retryAfterMillis);
    retryAtNanos = now + TimeUnit.MILLISECONDS.toNanos(delayMillis);
    state = State.OPEN;
    if (before == State.CLOSED) {
      lastTripMillis = wallClock.getAsLong();
    }
    events.transition(before, State.OPEN, error);
    return before;
  }

  /// Ends the backoff now, the next [#tryAcquire()] lets a probe through. Used for a last attempt
  /// when shutting down.
  public synchronized void skipBackoff() {
    if (state == State.OPEN) {
      retryAtNanos = nanoClock.getAsLong();
    }
  }

  /// End of the backoff, when the breaker lets a probe through. Meaningful while open.
  public synchronized long retryAtNanos() {
    return retryAtNanos;
  }

  public State state() {
    return state;
  }

  /// Failed attempts in a row, `0` when closed
  public synchronized int failures() {
    return failures;
  }

  /// Wall clock time the breaker was created, in milliseconds
  public long startMillis() {
    return startMillis;
  }

  /// Wall clock time of the last change from closed to open, `0` if none
  public long lastTripMillis() {
    return lastTripMillis;
  }

  /// Wall clock time of the last close after a trip, `0` if none
  public long lastRestartMillis() {
    return lastRestartMillis;
  }
}
