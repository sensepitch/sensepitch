package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProtectionConfig(
    boolean disable, DeflectorConfig deflector, List<CookieGateConfig> cookieGates) {}
