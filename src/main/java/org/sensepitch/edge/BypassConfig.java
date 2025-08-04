package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record BypassConfig(
  DetectCrawlerConfig detectCrawler,
  List<String> uris,
  List<String> remotes
) { }
