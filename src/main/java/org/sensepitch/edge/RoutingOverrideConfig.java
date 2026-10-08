package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// Routing that replaces the site's `upstream` and `paths` when triggered, see
/// [SiteConfig#overrides()]. The site's paths are not inherited.
///
/// @param whenCookie trigger: the override applies if a cookie with this name is present, the
///   value is ignored
/// @param upstream default upstream of the override, mandatory
/// @param paths upstreams by request path prefix, same as [SiteConfig#paths()]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record RoutingOverrideConfig(
    String whenCookie, UpstreamConfig upstream, Map<String, PathRouteConfig> paths) {}
