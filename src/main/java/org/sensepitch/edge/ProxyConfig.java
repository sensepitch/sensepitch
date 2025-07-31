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
  AdmissionConfig admission,
  UnservicedHostConfig unservicedHost,
  IpLookupConfig ipLookup,
  List<UpstreamConfig> upstream,
  Map<String, SiteConfig> sites
) {
}
