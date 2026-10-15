package org.sensepitch.edge;

import lombok.Builder;

/// Routing target for requests matching a path or path prefix, see [SiteConfig#paths()]. Exactly
// one of
/// `upstream` or `response` is set.
///
/// @param key the path prefix ending with `*`, e.g. `/api/*`, or an exact path, e.g. `/hello.html`.
///   Injected from the map key.
/// @param upstream the upstream for matching requests
/// @param response a fixed response for matching requests, e.g. to block a path with 404
/// @author Jens Wilke
@Builder(toBuilder = true)
public record PathRouteConfig(String key, UpstreamConfig upstream, ResponseConfig response)
    implements HasKey {}
