package org.sensepitch.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Test some netty behavior
 *
 * @author Jens Wilke
 */
public class IsolatedNettyTest {

  @Test
  public void testExceptionCaughtAndChannelClosed() {
    final AtomicBoolean wasCaught = new AtomicBoolean(false);
    EmbeddedChannel channel = new EmbeddedChannel();
    // Add HttpClientCodec to pipeline
    channel.pipeline().addLast(new HttpClientCodec());
    // Add our exception handler under test
    channel
        .pipeline()
        .addLast(
            new ChannelInboundHandlerAdapter() {
              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                // System.err.println(cause);
                wasCaught.set(true);
                ctx.close();
              }
            });
    // Simulate upstream exception
    channel.pipeline().fireExceptionCaught(new ClosedChannelException());
    assertTrue(wasCaught.get());
    assertFalse(channel.isActive(), "Channel should be closed after exception");
    channel.finishAndReleaseAll();
  }
}
