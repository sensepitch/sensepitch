package org.sensepitch.edge;

import lombok.Builder;

/// Handling of the W3C trace context, see [TraceContext].
///
/// The edge assigns each request a span id of its own and sends it to upstream in the
/// `traceparent` header.
///
/// @param trustTraceparent continue the trace of a valid `traceparent` header sent by the client,
///   and pass its `tracestate` on. Off by default: the edge faces the internet, so a client could
///   inject arbitrary trace ids. Then every request starts a new trace and `tracestate` is removed.
///   Turn it on when a trusted CDN or load balancer in front of the edge sets the header.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record TraceContextConfig(boolean trustTraceparent) {

  public static final TraceContextConfig DEFAULT = TraceContextConfig.builder().build();
}
