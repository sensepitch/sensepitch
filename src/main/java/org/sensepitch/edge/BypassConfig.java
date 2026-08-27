package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/// @author Jens Wilke
@Builder(toBuilder = true)
public record BypassConfig(
    DetectCrawlerConfig detectCrawler, List<String> uris, List<String> remotes) {}
