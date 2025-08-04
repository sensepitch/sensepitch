package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record DeflectorConfig(
  String serverIpv4Address,
  BypassConfig bypass,
  NoBypassConfig noBypass,
  DetectCrawlerConfig detectCrawler,
  List<AdmissionTokenGeneratorConfig> tokenGenerators
) { }
