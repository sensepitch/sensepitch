package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @param hashTargetPrefix the prefix that needs to be achieved by PoW, this is together with the
 *     length of the challenge this ia the difficulty, default is {@value
 *     DEFAULT_CHALLENGE_TARGET_PREFIX}
 * @param powMaxIterations maximum number of iterations for solving the PoW in the browser, default
 *     is {@value DEFAULT_POW_MAX_ITERATIONS}
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record DeflectorConfig(
    String serverIpv4Address,
    BypassConfig bypass,
    NoBypassConfig noBypass,
    DetectCrawlerConfig detectCrawler,
    String hashTargetPrefix,
    int powMaxIterations,
    List<AdmissionTokenGeneratorConfig> tokenGenerators) {

  public static DeflectorConfig.Builder builder() {
    return new DeflectorConfig.Builder()
        .hashTargetPrefix(DEFAULT_CHALLENGE_TARGET_PREFIX)
        .powMaxIterations(DEFAULT_POW_MAX_ITERATIONS);
  }

  public static final String DEFAULT_CHALLENGE_TARGET_PREFIX = "888";
  public static final int DEFAULT_POW_MAX_ITERATIONS = 1_000_000;
}
