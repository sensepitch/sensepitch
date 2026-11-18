package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  /// A fixed wall clock makes dates, including subsecond remaining delays, deterministic.
  static final long NOW = Instant.parse("2026-10-21T07:27:00Z").toEpochMilli();

  @ParameterizedTest
  @CsvSource({
    "' 2 ', 2000",
    "0, 0",
    "-5, 0",
    "+2, 2000",
    "300, 300000",
    "999999, 300000",
    "9223372036854775807, 300000",
    "9223372036854775808, 0",
    "1.5, 0",
    "garbage, 0",
    "'', 0",
    "'Wed, 21 Oct 2026 07:28:00 GMT', 60000",
    "'  Wed, 21 Oct 2026 07:28:00 GMT  ', 60000",
    "'Wed, 21 Oct 2026 07:26:59 GMT', 0",
    "'Wed, 21 Oct 2026 07:27:00 GMT', 0",
    "'Wed, 21 Oct 2026 07:32:00 GMT', 300000",
    "'Wed, 21 Oct 2026 08:28:00 GMT', 300000",
    "'Wed, 21 Oct 2026 25:28:00 GMT', 0",
    "'Wed, 21 Nope 2026 07:28:00 GMT', 0",
    "'Wednesday, 21-Oct-26 07:28:00 GMT', 60000",
    "'Wed Oct 21 07:28:00 2026', 60000"
  })
  void retryAfterParsing(String value, long expected) {
    assertThat(Backoff.parseRetryAfter(value, NOW)).isEqualTo(expected);
  }

  @Test
  void oversizedInvalidHeaderIsIgnored() {
    assertThat(Backoff.parseRetryAfter("x".repeat(65), NOW)).isZero();
    assertThat(Backoff.parseRetryAfter("x".repeat(64), NOW)).isZero();
  }

  @Test
  void dateDelayUsesMillisecondsAndCannotShortenBackoff() {
    String date = "Wed, 21 Oct 2026 07:28:00 GMT";
    assertThat(Backoff.parseRetryAfter(date, NOW + 500)).isEqualTo(59_500);
    assertThat(LOWEST.delayMillis(0, Backoff.parseRetryAfter(date, NOW))).isEqualTo(60_000);
    assertThat(LOWEST.delayMillis(3, Backoff.parseRetryAfter(date, NOW + 59_000))).isEqualTo(4_000);
    assertThat(LOWEST.delayMillis(3, Backoff.parseRetryAfter(date, NOW + 61_000))).isEqualTo(4_000);
  }

  @Test
  void retryAfterHeaderExtraction() {
    assertThat(Backoff.retryAfterMillis(response(Map.of()), NOW)).isZero();
    assertThat(Backoff.retryAfterMillis(response(Map.of("Retry-After", List.of("garbage"))), NOW))
        .isZero();
    assertThat(Backoff.retryAfterMillis(response(Map.of("retry-after", List.of("2"))), NOW))
        .isEqualTo(2_000);
    assertThat(
            Backoff.retryAfterMillis(
                response(Map.of("Retry-After", List.of("Wed, 21 Oct 2026 07:28:00 GMT", "120"))),
                NOW))
        .as("the first header value is used")
        .isEqualTo(60_000);
    assertThat(Backoff.retryAfterMillis(response(Map.of("Retry-After", List.of("2")))))
        .isEqualTo(2_000);
    assertThat(Backoff.parseRetryAfter("2")).isEqualTo(2_000);
  }

  /// Supplies only headers, so extraction needs neither a real server nor a sleep.
  private static HttpResponse<?> response(Map<String, List<String>> headers) {
    return (HttpResponse<?>)
        java.lang.reflect.Proxy.newProxyInstance(
            BackoffTest.class.getClassLoader(),
            new Class<?>[] {HttpResponse.class},
            (proxy, method, args) -> HttpHeaders.of(headers, (name, value) -> true));
  }
}
