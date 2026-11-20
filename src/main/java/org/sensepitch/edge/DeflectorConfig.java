package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/// @param hashTargetPrefix the prefix that needs to be achieved by PoW, this is together with the
///   length of the challenge this ia the difficulty, default is {@value
///   #DEFAULT_CHALLENGE_TARGET_PREFIX}
/// @param powMaxIterations maximum number of iterations for solving the PoW in the browser, default
///   is {@value #DEFAULT_POW_MAX_ITERATIONS}
/// @param admissionMaxAgeMillis maximum age of a granted admission token; an older token is
///   rejected and re-challenged, default is {@value #DEFAULT_ADMISSION_MAX_AGE_MILLIS}
/// @param admissionClockSkewMillis allowance for a token whose creation time lies in the future,
///   for clock skew between issuing and verifying servers, default is {@value
///   #DEFAULT_ADMISSION_CLOCK_SKEW_MILLIS}
/// @author Jens Wilke
@Builder(toBuilder = true)
public record DeflectorConfig(
    String serverIpv4Address,
    BypassConfig bypass,
    NoBypassConfig noBypass,
    DetectCrawlerConfig detectCrawler,
    String hashTargetPrefix,
    int powMaxIterations,
    List<AdmissionTokenGeneratorConfig> tokenGenerators,
    long admissionMaxAgeMillis,
    long admissionClockSkewMillis) {

  public static DeflectorConfig.Builder builder() {
    return new DeflectorConfig.Builder()
        .hashTargetPrefix(DEFAULT_CHALLENGE_TARGET_PREFIX)
        .powMaxIterations(DEFAULT_POW_MAX_ITERATIONS)
        .admissionMaxAgeMillis(DEFAULT_ADMISSION_MAX_AGE_MILLIS)
        .admissionClockSkewMillis(DEFAULT_ADMISSION_CLOCK_SKEW_MILLIS);
  }

  public static final String DEFAULT_CHALLENGE_TARGET_PREFIX = "888";
  public static final int DEFAULT_POW_MAX_ITERATIONS = 1_000_000;
  public static final long DEFAULT_ADMISSION_MAX_AGE_MILLIS = 5L * 24 * 60 * 60 * 1000;
  public static final long DEFAULT_ADMISSION_CLOCK_SKEW_MILLIS = 5_000;

  /// @throws IllegalArgumentException if the admission maximum age is not positive or the clock
  ///   skew allowance is negative
  public DeflectorConfig {
    if (admissionMaxAgeMillis <= 0) {
      throw new IllegalArgumentException("admissionMaxAgeMillis must be positive");
    }
    if (admissionClockSkewMillis < 0) {
      throw new IllegalArgumentException("admissionClockSkewMillis must not be negative");
    }
  }
}
