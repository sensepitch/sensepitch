package org.sensepitch.edge;

/**
 * Blocks access if the specified cookie is not present. The content of the cookie is not relevant.
 *
 * @param name
 * @param accessUri if the
 *
 * @author Jens Wilke
 */
public record CookieGateConfig(
  String name,
  String accessUri,
  String redirectLocation
) {  }
