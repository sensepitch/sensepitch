package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 *
 * @param hashTargetPrefix the prefix that needs to be achieved by PoW, this is
 *                        together with the length of the challenge this ia the difficulty, default
 *                        is {@value DEFAULT_CHALLENGE_TARGET_PREFIX}
 *
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record DeflectorConfig(
    String serverIpv4Address,
    BypassConfig bypass,
    NoBypassConfig noBypass,
    DetectCrawlerConfig detectCrawler,
    String hashTargetPrefix,
    List<AdmissionTokenGeneratorConfig> tokenGenerators) {

  public static final String DEFAULT_CHALLENGE_TARGET_PREFIX = "888";

  public final static DeflectorConfig DEFAULT = DeflectorConfig.builder()
    .hashTargetPrefix(DEFAULT_CHALLENGE_TARGET_PREFIX)
    .build();


}
