package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProtectionBypassConfig(
  List<String> uris, List<String> remotes
) {
}
