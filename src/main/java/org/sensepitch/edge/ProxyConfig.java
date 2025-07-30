package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record ProxyConfig(
  MetricsConfig metrics,
  ListenConfig listen,
  AdmissionConfig admission,
  UnservicedHostConfig unservicedHost,
  IpLookupConfig ipLookup,
  List<UpstreamConfig> upstream,
  List<SiteConfig> sites
) {
}
