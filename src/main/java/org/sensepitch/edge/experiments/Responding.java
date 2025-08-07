package org.sensepitch.edge.experiments;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Jens Wilke
 */
public class Responding {

  static final ByteBuf RANDOM6909 = createUnreleasableRandomBuf(6906);

  private static ByteBuf createUnreleasableRandomBuf(int size) {
    byte[] data = new byte[size];
    ThreadLocalRandom.current().nextBytes(data);
    // Wrap the byte[] in a ByteBuf and make it unreleasable
    return Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(data));
  }

  private static void sendStaticResponse(ChannelHandlerContext ctx) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set("Date", new Date());
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 6906);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/jpeg");
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    response.content().writeBytes(RANDOM6909.duplicate());
    ctx.writeAndFlush(response);
  }
}
