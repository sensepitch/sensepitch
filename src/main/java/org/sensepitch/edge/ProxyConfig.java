package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

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
    Map<String, SiteConfig> sites) {}
