package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;

/**
 * @author Jens Wilke
 */
public interface Upstream {

  Future<Channel> connect(ChannelHandlerContext downstreamContext);

  void release(Channel ch);
}
