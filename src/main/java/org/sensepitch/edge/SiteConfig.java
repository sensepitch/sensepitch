package org.sensepitch.edge;

import lombok.Builder;

/// @author Jens Wilke
@Builder(toBuilder = true)
public record SiteConfig(
    String key,
    String host,
    String uri,
    ResponseConfig response,
    FallbackConfig fallback,
    UpstreamConfig upstream,
    ProtectionConfig protection)
    implements HasKey {}
