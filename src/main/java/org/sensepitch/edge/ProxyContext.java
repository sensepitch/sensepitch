package org.sensepitch.edge;

import io.netty.channel.EventLoopGroup;

/**
 * @author Jens Wilke
 */
public interface ProxyContext {

  EventLoopGroup eventLoopGroup();
  ProxyMetrics metrics();

}
