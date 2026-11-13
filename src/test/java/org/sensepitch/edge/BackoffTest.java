package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class BackoffTest {

  /// The lowest jitter, half the backoff
  static final Backoff LOWEST = new Backoff(1_000, 30_000, bound -> 0);

  /// The highest jitter, the full backoff
  static final Backoff HIGHEST = new Backoff(1_000, 30_000, bound -> bound - 1);

  /// Exponential backoff with jitter, capped, a longer requested delay wins
  @Test
  void retryDelay() {
    assertThat(LOWEST.delayMillis(0, 0)).isEqualTo(500);
    assertThat(HIGHEST.delayMillis(0, 0)).isEqualTo(1_000);
    assertThat(LOWEST.delayMillis(3, 0)).isEqualTo(4_000);
    assertThat(LOWEST.delayMillis(10, 0)).as("capped").isEqualTo(15_000);
    assertThat(HIGHEST.delayMillis(100, 0))
        .as("no overflow for many retries, at most the maximum")
        .isEqualTo(30_000);
    assertThat(LOWEST.delayMillis(0, 120_000)).as("longer Retry-After wins").isEqualTo(120_000);
    assertThat(LOWEST.delayMillis(3, 1_000))
        .as("shorter Retry-After does not shorten the backoff")
        .isEqualTo(4_000);
    assertThat(new Backoff(1, 1, bound -> 0).delayMillis(0, 0)).as("at least 1 ms").isOne();
  }

  /// The default jitter stays within the bound
  @Test
  void threadLocalRandomWithinBound() {
    for (int i = 0; i < 100; i++) {
      assertThat(RandomNumberProvider.THREAD_LOCAL.random(3)).isBetween(0L, 2L);
    }
  }

  @Test
  void retryAfterParsing() {
    assertThat(Backoff.parseRetryAfter(" 2 ")).isEqualTo(2_000);
    assertThat(Backoff.parseRetryAfter("-5")).isZero();
    assertThat(Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isZero();
    assertThat(Backoff.parseRetryAfter("999999")).isEqualTo(Backoff.MAX_RETRY_AFTER_MILLIS);
  }
}
