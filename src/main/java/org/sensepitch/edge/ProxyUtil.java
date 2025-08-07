package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;

/**
 * @author Jens Wilke
 */
public final class ProxyUtil {

  public static String extractRemoteIp(ChannelHandlerContext ctx) {
    return extractRemoteIp(ctx.channel());
  }

  public static String extractRemoteIp(Channel channel) {
    if (channel instanceof EmbeddedChannel) {
      return "embedded";
    }
    return ((SocketChannel) channel).remoteAddress().getAddress().getHostAddress();
  }
}
