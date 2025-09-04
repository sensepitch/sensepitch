package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @param uris List of URIs that POW challenge, no prefix or suffix is supported
 * @author Jens Wilke
 */
@Builder
public record NoBypassConfig(List<String> uris) {}
