package org.sensepitch.edge;

import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

/// Exponential backoff with jitter for retrying a failed remote, see [CircuitBreaker].
///
/// @param initialMillis delay after the first failure, before jitter
/// @param maxMillis upper bound of the delay, before jitter
/// @param random source of the jitter
/// @author Jens Wilke
public record Backoff(long initialMillis, long maxMillis, RandomNumberProvider random) {

  /// With jitter from [RandomNumberProvider#THREAD_LOCAL]
  public Backoff(long initialMillis, long maxMillis) {
    this(initialMillis, maxMillis, RandomNumberProvider.THREAD_LOCAL);
  }

  /// Upper bound of a delay requested via `Retry-After`
  public static final long MAX_RETRY_AFTER_MILLIS = 300_000;

  /// Delay before the next attempt: exponential backoff from `initialMillis` up to `maxMillis`,
  /// of which a random half is taken, so a fleet of proxies does not retry in lockstep. A longer
  /// delay the server requested via `Retry-After` wins, a shorter one does not shorten the
  /// backoff.
  ///
  /// @param failures failed attempts in a row before this one
  /// @param retryAfterMillis delay the server requested, `0` if none
  /// @return the delay, at least one millisecond
  public long delayMillis(int failures, long retryAfterMillis) {
    long half = Math.min(maxMillis, initialMillis << Math.min(failures, 20)) / 2;
    long delay = half + random.random(half + 1);
    return Math.max(1, Math.max(delay, retryAfterMillis));
  }

  /// Delay requested by the server in seconds, the HTTP date form is not supported
  ///
  /// @return the delay in milliseconds, `0` if none
  public static long retryAfterMillis(HttpResponse<?> response) {
    return response.headers().firstValue("Retry-After").map(Backoff::parseRetryAfter).orElse(0L);
  }

  /// Parses the seconds form of `Retry-After`, capped at {@value #MAX_RETRY_AFTER_MILLIS} ms
  ///
  /// @return the delay in milliseconds, `0` if the value is not a number of seconds
  public static long parseRetryAfter(String value) {
    try {
      long seconds = Math.max(0, Long.parseLong(value.trim()));
      return Math.min(MAX_RETRY_AFTER_MILLIS, TimeUnit.SECONDS.toMillis(seconds));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
