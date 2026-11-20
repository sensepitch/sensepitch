package org.sensepitch.edge;

import io.netty.channel.EventLoopGroup;

/// @author Jens Wilke
public interface ProxyContext {

  EventLoopGroup eventLoopGroup();

  ProxyMetrics metrics();

  /// The environment the configuration defaults are derived from, see [ProxyEnvironment]
  ProxyEnvironment environment();
}
