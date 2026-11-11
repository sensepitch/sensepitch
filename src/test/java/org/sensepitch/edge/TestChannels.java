package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/// Channels for tests
///
/// @author Jens Wilke
final class TestChannels {

  private TestChannels() {}

  /// An embedded channel with a real remote address, like a TCP connection from a client. A plain
  /// [EmbeddedChannel] has no [InetSocketAddress].
  static Channel channelFrom(String ip) {
    return new EmbeddedChannel() {
      @Override
      protected SocketAddress remoteAddress0() {
        return new InetSocketAddress(ip, 4711);
      }
    };
  }
}
