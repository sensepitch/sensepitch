package org.sensepitch.edge;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/// @param upstream default upstream of the site, used when no path matches
/// @param paths upstreams by request path prefix, the map key is the prefix ending with `*`. The
///   longest matching prefix wins.
/// @param overrides alternative routings that replace `upstream` and `paths` when triggered, e.g.
///   to switch a whole beta setup by cookie. The first triggered override wins.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record SiteConfig(
    String key,
    String host,
    String uri,
    ResponseConfig response,
    FallbackConfig fallback,
    UpstreamConfig upstream,
    Map<String, PathRouteConfig> paths,
    List<RoutingOverrideConfig> overrides,
    ProtectionConfig protection)
    implements HasKey {}
