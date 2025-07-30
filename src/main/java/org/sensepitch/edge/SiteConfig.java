package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record SiteConfig (
  String key,
  String host,
  String uri,
  String responseText,
  int responseStatusCode,
  String upstreamRef,
  UpstreamConfig upstream,
  ProtectionConfig protection
) { }
