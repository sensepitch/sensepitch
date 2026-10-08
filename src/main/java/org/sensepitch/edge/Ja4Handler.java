package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

/// Computes the JA4 fingerprint of the TLS ClientHello and stores it in a channel attribute.
///
/// <p>Needs to be placed before the handler that terminates TLS, since it inspects the raw bytes
/// the client sends. The bytes are passed on unchanged, the handler only looks at a copy. Once the
/// fingerprint is known, or it is clear that there will be none, the handler removes itself from
/// the pipeline, so there is no cost per connection beyond the handshake.
///
/// <p>Holds per connection state, a new instance is needed for every channel.
///
/// @see ClientHelloParser
public class Ja4Handler extends ChannelInboundHandlerAdapter {

  public static final AttributeKey<Ja4Fingerprint> ATTRIBUTE =
      AttributeKey.valueOf(Ja4Fingerprint.class.getName());

  /// A ClientHello beyond that size is either not a ClientHello or not worth the memory. The TLS
  /// record layer allows 16k per record.
  static final int MAX_CLIENT_HELLO_BYTES = 16 * 1024;

  static final ProxyLogger LOG = ProxyLogger.get(Ja4Handler.class);

  private ByteBuf received;

  /// @return the fingerprint of the connection or {@code null} if there is none, e.g. because the
  ///   ClientHello was malformed or JA4 is not enabled
  public static Ja4Fingerprint lookup(Channel channel) {
    return channel.attr(ATTRIBUTE).get();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof ByteBuf buf) {
      try {
        inspect(ctx, buf);
      } catch (Throwable t) {
        // a fingerprint is never worth losing a connection for
        LOG.error(ctx.channel(), "JA4 fingerprint failed", t);
        finish(ctx);
      }
    }
    super.channelRead(ctx, msg);
  }

  private void inspect(ChannelHandlerContext ctx, ByteBuf buf) {
    if (!buf.isReadable()) {
      return;
    }
    if (received == null) {
      if (buf.getUnsignedByte(buf.readerIndex()) != ClientHelloParser.RECORD_TYPE_HANDSHAKE) {
        // not TLS, e.g. a plain HTTP request sent to the HTTPS port
        finish(ctx);
        return;
      }
      received = Unpooled.buffer(buf.readableBytes());
    }
    // the ClientHello may be spread over several reads, collect until it is complete
    received.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
    Ja4Fingerprint fingerprint = compute(received);
    if (fingerprint != null) {
      ctx.channel().attr(ATTRIBUTE).set(fingerprint);
      if (LOG.isTraceEnabled()) {
        LOG.trace(ctx.channel(), "ja4=" + fingerprint.value() + " raw=" + fingerprint.raw());
      }
      finish(ctx);
    } else if (received.readableBytes() > MAX_CLIENT_HELLO_BYTES) {
      LOG.trace(ctx.channel(), "no JA4 fingerprint, ClientHello too big or malformed");
      finish(ctx);
    }
  }

  static Ja4Fingerprint compute(ByteBuf clientHello) {
    ClientHelloInfo hello = ClientHelloParser.parse(clientHello);
    return hello == null ? null : Ja4Fingerprint.of(hello);
  }

  /// Nothing more to look at, drop the collected bytes and step out of the pipeline.
  private void finish(ChannelHandlerContext ctx) {
    release();
    if (!ctx.isRemoved()) {
      ctx.pipeline().remove(this);
    }
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    release();
    super.handlerRemoved(ctx);
  }

  private void release() {
    if (received != null) {
      received.release();
      received = null;
    }
  }
}
