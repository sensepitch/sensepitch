package org.sensepitch.edge;

import lombok.Builder;

import java.util.Set;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record UnservicedHostConfig(
  String defaultLocation,
  Set<String> servicedDomains
) { }
