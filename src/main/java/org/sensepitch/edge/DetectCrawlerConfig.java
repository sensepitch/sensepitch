package org.sensepitch.edge;

import lombok.Builder;

/**
 * @param disableBuiltinDatabase disable the crawler database that ships with Sensepitch
 * @param crawlerTsv additional or alternative crawler database
 * @author Jens Wilke
 */
@Builder
public record DetectCrawlerConfig(boolean disableBuiltinDatabase, String crawlerTsv) {}
