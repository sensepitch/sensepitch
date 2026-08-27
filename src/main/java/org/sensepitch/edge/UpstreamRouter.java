package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpRequest;

/// @author Jens Wilke
public interface UpstreamRouter {
  Upstream selectUpstream(HttpRequest request);
}
