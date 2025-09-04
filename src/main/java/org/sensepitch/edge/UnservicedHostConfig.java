package org.sensepitch.edge;

import java.util.Set;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record UnservicedHostConfig(String defaultLocation, Set<String> servicedDomains) {}
