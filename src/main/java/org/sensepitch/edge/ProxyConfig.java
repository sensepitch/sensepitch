package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

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
    ProtectionConfig protection,
    RequestLogConfig requestLog,
    Map<String, SiteConfig> sites) {}
