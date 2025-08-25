package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record DetectCrawlerConfig(boolean disableDefault, String crawlerTsv) {}
