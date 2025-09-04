package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record DeflectorConfig(
    String serverIpv4Address,
    BypassConfig bypass,
    NoBypassConfig noBypass,
    DetectCrawlerConfig detectCrawler,
    List<AdmissionTokenGeneratorConfig> tokenGenerators) {}
