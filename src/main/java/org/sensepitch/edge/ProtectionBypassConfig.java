package org.sensepitch.edge;

import java.util.List;

/**
 * @author Jens Wilke
 */
public record ProtectionBypassConfig(
  List<String> uris, List<String> remotes
) {
}
