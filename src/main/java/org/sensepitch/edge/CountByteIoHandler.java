package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * @author Jens Wilke
 */
public class CountByteIoHandler extends ChannelDuplexHandler {

  private long received;
  private long sent;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    assert msg instanceof ByteBuf;
    received += ((ByteBuf) msg).readableBytes();
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    assert msg instanceof ByteBuf;
    sent += ((ByteBuf) msg).readableBytes();
    super.write(ctx, msg, promise);
  }

  public long getBytesReceived() {
    return received;
  }

  public long getBytesSent() {
    return sent;
  }
}
