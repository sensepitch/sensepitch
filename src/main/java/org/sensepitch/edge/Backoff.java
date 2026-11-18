package org.sensepitch.edge;

import io.netty.handler.codec.DateFormatter;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/// Exponential backoff with jitter for retrying a failed remote, see [CircuitBreaker].
/// A server's `Retry-After` in seconds or as an HTTP date can extend the backoff, capped at
/// {@value #MAX_RETRY_AFTER_MILLIS} ms. Dates are relative to the wall clock when parsed;
/// invalid values and dates at or before that time add no delay.
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

  /// Delay requested by the server in seconds or as an HTTP date, relative to the system clock
  ///
  /// @return the delay in milliseconds, `0` if none
  public static long retryAfterMillis(HttpResponse<?> response) {
    return retryAfterMillis(response, System.currentTimeMillis());
  }

  /// Extracts the requested delay with an explicit wall clock for deterministic tests.
  static long retryAfterMillis(HttpResponse<?> response, long nowMillis) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(value -> parseRetryAfter(value, nowMillis))
        .orElse(0L);
  }

  /// Parses `Retry-After` relative to the system clock, see [#parseRetryAfter(String, long)].
  public static long parseRetryAfter(String value) {
    return parseRetryAfter(value, System.currentTimeMillis());
  }

  /// Parses seconds or an HTTP date, capped at {@value #MAX_RETRY_AFTER_MILLIS} ms.
  /// Past and current dates request no delay; invalid values leave the backoff unchanged.
  ///
  /// @param value header value
  /// @param nowMillis current wall clock time in milliseconds since the epoch
  /// @return the delay in milliseconds, `0` if invalid or not in the future
  public static long parseRetryAfter(String value, long nowMillis) {
    String trimmed = value.trim();
    try {
      long seconds = Math.max(0, Long.parseLong(trimmed));
      return Math.min(MAX_RETRY_AFTER_MILLIS, TimeUnit.SECONDS.toMillis(seconds));
    } catch (NumberFormatException e) {
      // Netty rejects inputs over 64 characters with an exception; a bad header is no delay.
      if (trimmed.length() > 64) {
        return 0;
      }
      Date date = DateFormatter.parseHttpDate(trimmed);
      return date == null ? 0 : Math.clamp(date.getTime() - nowMillis, 0, MAX_RETRY_AFTER_MILLIS);
    }
  }
}
