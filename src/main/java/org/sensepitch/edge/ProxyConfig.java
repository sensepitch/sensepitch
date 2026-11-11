package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// @param admin admin HTTP endpoint, not started if absent, see [AdminServer]
/// @param upstream default upstream for sites without an upstream of their own
/// @param upstreams named upstreams, referenced via [UpstreamConfig#ref()]
/// @param requestLog request log destinations and format, see [RequestLogConfig]
/// @param traceContext handling of the W3C trace context, see [TraceContextConfig]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ProxyConfig(
    MetricsConfig metrics,
    AdminConfig admin,
    ListenConfig listen,
    UnservicedHostConfig unservicedHost,
    IpLookupConfig ipLookup,
    Ja4Config ja4,
    FallbackConfig fallback,
    UpstreamConfig upstream,
    Map<String, UpstreamConfig> upstreams,
    ProtectionConfig protection,
    RequestLogConfig requestLog,
    TraceContextConfig traceContext,
    Map<String, SiteConfig> sites) {}
