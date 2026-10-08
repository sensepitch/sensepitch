package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// @param upstream default upstream for sites without an upstream of their own
/// @param upstreams named upstreams, referenced via [UpstreamConfig#ref()]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ProxyConfig(
    MetricsConfig metrics,
    ListenConfig listen,
    UnservicedHostConfig unservicedHost,
    IpLookupConfig ipLookup,
    Ja4Config ja4,
    FallbackConfig fallback,
    UpstreamConfig upstream,
    Map<String, UpstreamConfig> upstreams,
    ProtectionConfig protection,
    RequestLogConfig requestLog,
    Map<String, SiteConfig> sites) {}
