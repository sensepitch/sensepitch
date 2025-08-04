package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProxyConfig(
  MetricsConfig metrics,
  ListenConfig listen,
  UnservicedHostConfig unservicedHost,
  IpLookupConfig ipLookup,
  UpstreamConfig upstream,
  ProtectionConfig protection,
  Map<String, SiteConfig> sites
) {
}
