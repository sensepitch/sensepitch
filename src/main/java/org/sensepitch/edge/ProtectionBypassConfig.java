package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProtectionBypassConfig(List<String> uris, List<String> remotes) {}
