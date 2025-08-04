package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProtectionConfig(
  boolean disable,
  DeflectorConfig deflector,
  List<CookieGateConfig> cookieGates
) { }
