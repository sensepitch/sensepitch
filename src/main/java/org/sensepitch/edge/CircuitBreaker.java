package org.sensepitch.edge;

import io.netty.util.concurrent.Ticker;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;

/// Stops requests to a remote that failed, and lets a single probe through after a backoff.
///
/// - `CLOSED`: requests pass, see [#tryAcquire()].
/// - `OPEN`: after a failure, see [#onFailure(long, String)], requests are refused until the backoff is
///   over. A failure of a request that was still in flight when the breaker opened does not
///   count again.
/// - `HALF_OPEN`: once the backoff is over, [#tryAcquire()] lets exactly one request through as
///   a probe. Its success closes the breaker, as does an ignored failure, see
///   [#onIgnored(String)], since the server answered. Its failure opens it again with a doubled
///   backoff.
///   A probe that reports nothing within the probe timeout lets the next one through, so the
///   breaker cannot get stuck.
///
/// The breaker is passive: it has no thread or timer, a caller that wants to retry by itself
/// schedules after [#retryDelayNanos()]. It is used for posting request logs, see
/// [HttpBatchSender]. It opens on the first failure, which suits a sender that retries one batch
/// after a failure. Before it guards the connections to an upstream, which fail one at a time
/// under load, it needs a failure threshold, e.g. consecutive failures or a failure rate over a
/// window as in Armeria or resilience4j.
///
/// [#tryAcquire()] in the closed state reads one volatile field, it neither locks nor allocates,
/// so it can be called for each request on an event loop. State changes take an internal lock.
///
/// Every outcome, refusal and state change is reported to the [Events], e.g. the metrics, see
/// [CircuitBreakerMetrics]. The breaker keeps the wall clock times of its start, its last trip and
/// its last restart for them.
///
/// The durations, the backoff and the probe timeout, come from a [Ticker]; the wall clock times
/// for the metrics come from the system clock, so they match the timestamps of the logs. A test
/// controls both, see [#CircuitBreaker(Backoff, long, Events, Ticker, InstantSource)].
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

  /// Receives what the breaker does, mostly with the internal lock of the breaker held, so an
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
  private final Ticker ticker;

  /// Wall clock, for the start, trip and restart times
  private final InstantSource wallClock;

  private final long startMillis;

  private final Events events;

  /// Guards all state changes and the fields below, private so no outside code can hold it
  private final Object lock = new Object();

  /// Written under the lock, volatile for the lock free read in [#tryAcquire()] and [#state()]
  private volatile State state = State.CLOSED;

  // guarded by lock
  private int failures;
  private long retryAtNanos;
  private long probeSinceNanos;

  /// Wall clock time of the last change from closed to open, `0` if none
  private long lastTripMillis;

  /// Wall clock time of the last close after a trip, `0` if none
  private long lastRestartMillis;

  /// @param probeTimeoutMillis time after which a probe that reported nothing is replaced, at
  ///   least the request timeout
  /// @param events receives what the breaker does, e.g. [CircuitBreakerMetrics#events(String, String)]
  public CircuitBreaker(Backoff backoff, long probeTimeoutMillis, Events events) {
    this(backoff, probeTimeoutMillis, events, Ticker.systemTicker(), InstantSource.system());
  }

  /// With a ticker and a wall clock, for tests
  ///
  /// @param wallClock read at the start, a trip and a restart
  CircuitBreaker(
      Backoff backoff,
      long probeTimeoutMillis,
      Events events,
      Ticker ticker,
      InstantSource wallClock) {
    this.backoff = backoff;
    this.events = events;
    this.probeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(probeTimeoutMillis);
    this.ticker = ticker;
    this.wallClock = wallClock;
    this.startMillis = wallClock.millis();
  }

  /// Asks for permission to send a request.
  ///
  /// @return `true` if the request may be sent; in the half open state it is the probe and must
  ///   be followed by [#onSuccess()], [#onIgnored(String)] or [#onFailure(long, String)]. A
  ///   refusal is reported as [Kind#NOT_PERMITTED].
  public boolean tryAcquire() {
    return state == State.CLOSED || tryAcquireSlow();
  }

  private boolean tryAcquireSlow() {
    synchronized (lock) {
      long now = ticker.nanoTime();
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
  }

  /// A request succeeded, the breaker closes.
  ///
  /// @return the state before, not `CLOSED` means the remote recovered
  public State onSuccess() {
    synchronized (lock) {
      events.call(Kind.SUCCESSFUL);
      State before = state;
      if (before != State.CLOSED) {
        close(before);
      }
      return before;
    }
  }

  /// A request failed in a way a retry cannot fix, e.g. a status 400, reported as [Kind#IGNORED].
  /// It does not open the breaker. If the breaker is not closed, it closes as on [#onSuccess()]:
  /// the server answered, so it is up. Only releasing the probe would let the next probe through
  /// at once, which then most likely succeeds; closing saves that round and the slow start of the
  /// caller begins right away.
  ///
  /// @param error short code of the error, see [Events#error(String)]
  /// @return the state before, not `CLOSED` means the remote recovered
  public State onIgnored(String error) {
    synchronized (lock) {
      events.call(Kind.IGNORED);
      events.error(error);
      State before = state;
      if (before != State.CLOSED) {
        close(before);
      }
      return before;
    }
  }

  /// Closes the breaker after the remote answered, for [#onSuccess()] and [#onIgnored(String)]
  private void close(State before) {
    state = State.CLOSED;
    failures = 0;
    lastRestartMillis = wallClock.millis();
    events.transition(before, State.CLOSED, RECOVERED);
  }

  /// A request failed in a way that a later attempt may fix, e.g. a connection error or a status
  /// 503. The breaker opens for the backoff, or longer if the server requested it.
  ///
  /// @param retryAfterMillis delay the server requested via `Retry-After`, `0` if none
  /// @param error short code of the error, see [Events#error(String)], also the reason of the
  ///   transition to `OPEN`
  /// @return the state before: `CLOSED` means an outage starts, `OPEN` means the failure was
  ///   counted already, the caller does not need to schedule another retry
  public State onFailure(long retryAfterMillis, String error) {
    synchronized (lock) {
      events.call(Kind.FAILED);
      events.error(error);
      State before = state;
      long now = ticker.nanoTime();
      if (before == State.OPEN) {
        // a request in flight when the breaker opened, only a longer requested delay matters
        retryAtNanos =
            Math.max(retryAtNanos, now + TimeUnit.MILLISECONDS.toNanos(retryAfterMillis));
        return before;
      }
      long delayMillis = backoff.delayMillis(failures++, retryAfterMillis);
      retryAtNanos = now + TimeUnit.MILLISECONDS.toNanos(delayMillis);
      state = State.OPEN;
      if (before == State.CLOSED) {
        lastTripMillis = wallClock.millis();
      }
      events.transition(before, State.OPEN, error);
      return before;
    }
  }

  /// Ends the backoff now, the next [#tryAcquire()] lets a probe through. Used for a last attempt
  /// when shutting down.
  public void skipBackoff() {
    synchronized (lock) {
      if (state == State.OPEN) {
        retryAtNanos = ticker.nanoTime();
      }
    }
  }

  /// Time until the backoff is over and the breaker lets a probe through, `0` if it is over.
  /// Meaningful while open.
  public long retryDelayNanos() {
    synchronized (lock) {
      return Math.max(0, retryAtNanos - ticker.nanoTime());
    }
  }

  public State state() {
    return state;
  }

  /// Failed attempts in a row, `0` when closed
  public int failures() {
    synchronized (lock) {
      return failures;
    }
  }

  /// Wall clock time the breaker was created, in milliseconds
  public long startMillis() {
    return startMillis;
  }

  /// Wall clock time of the last change from closed to open, `0` if none
  public long lastTripMillis() {
    synchronized (lock) {
      return lastTripMillis;
    }
  }

  /// Wall clock time of the last close after a trip, `0` if none
  public long lastRestartMillis() {
    synchronized (lock) {
      return lastRestartMillis;
    }
  }
}
