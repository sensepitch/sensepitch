package org.sensepitch.edge;

import lombok.Builder;

/**
 *
 * @param target target host with optional port number. Names are supported, however the standard
 *  *             Java DNS resolver is used
 *
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record UpstreamConfig (
  String target,
  ConnectionPoolConfig connectionPool
) { }
