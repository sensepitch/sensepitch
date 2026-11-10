package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// Builds TLS ClientHello bytes for tests of [ClientHelloParser], [Ja4Fingerprint] and
/// [Ja4Handler]. Either synthetic, with full control over every field, or a real one produced by
/// the JDK TLS implementation via [#captureJdkClientHello()].
///
/// @author Jens Wilke
public class ClientHelloBuilder {

  private int legacyVersion = 0x0303;
  private int[] ciphers = new int[0];
  private final List<Integer> extensionTypes = new ArrayList<>();
  private final List<byte[]> extensionData = new ArrayList<>();
  private boolean omitExtensionBlock;

  public ClientHelloBuilder legacyVersion(int version) {
    this.legacyVersion = version;
    return this;
  }

  public ClientHelloBuilder ciphers(int... ciphers) {
    this.ciphers = ciphers;
    return this;
  }

  /// Leave out the extension block altogether, as allowed for old clients.
  public ClientHelloBuilder noExtensionBlock() {
    this.omitExtensionBlock = true;
    return this;
  }

  public ClientHelloBuilder extension(int type, byte[] data) {
    extensionTypes.add(type);
    extensionData.add(data);
    return this;
  }

  public ClientHelloBuilder extension(int type) {
    return extension(type, new byte[0]);
  }

  public ClientHelloBuilder sni(String host) {
    byte[] name = host.getBytes(StandardCharsets.US_ASCII);
    ByteBuf buf = Unpooled.buffer();
    buf.writeShort(name.length + 3);
    buf.writeByte(0); // host_name
    buf.writeShort(name.length);
    buf.writeBytes(name);
    return extension(ClientHelloParser.EXT_SERVER_NAME, bytes(buf));
  }

  public ClientHelloBuilder alpn(String... protocols) {
    ByteBuf list = Unpooled.buffer();
    for (String protocol : protocols) {
      byte[] p = protocol.getBytes(StandardCharsets.ISO_8859_1);
      list.writeByte(p.length);
      list.writeBytes(p);
    }
    ByteBuf buf = Unpooled.buffer();
    buf.writeShort(list.readableBytes());
    buf.writeBytes(list);
    return extension(ClientHelloParser.EXT_ALPN, bytes(buf));
  }

  public ClientHelloBuilder supportedVersions(int... versions) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeByte(versions.length * 2);
    for (int v : versions) {
      buf.writeShort(v);
    }
    return extension(ClientHelloParser.EXT_SUPPORTED_VERSIONS, bytes(buf));
  }

  public ClientHelloBuilder signatureAlgorithms(int... algorithms) {
    return extension(ClientHelloParser.EXT_SIGNATURE_ALGORITHMS, uint16List(algorithms));
  }

  /// @return the handshake message, without the TLS record header
  public byte[] handshake() {
    ByteBuf body = Unpooled.buffer();
    body.writeShort(legacyVersion);
    body.writeZero(32); // random
    body.writeByte(32); // session id length
    body.writeZero(32);
    body.writeBytes(uint16List(ciphers));
    body.writeByte(1); // compression methods
    body.writeByte(0);
    if (!omitExtensionBlock) {
      ByteBuf ext = Unpooled.buffer();
      for (int i = 0; i < extensionTypes.size(); i++) {
        ext.writeShort(extensionTypes.get(i));
        ext.writeShort(extensionData.get(i).length);
        ext.writeBytes(extensionData.get(i));
      }
      body.writeShort(ext.readableBytes());
      body.writeBytes(ext);
    }
    ByteBuf msg = Unpooled.buffer();
    msg.writeByte(ClientHelloParser.HANDSHAKE_TYPE_CLIENT_HELLO);
    msg.writeMedium(body.readableBytes());
    msg.writeBytes(body);
    return bytes(msg);
  }

  /// @return the ClientHello in a single TLS record
  public ByteBuf build() {
    return records(Integer.MAX_VALUE);
  }

  /// @return the ClientHello split into TLS records with at most `fragmentLength` payload bytes
  public ByteBuf records(int fragmentLength) {
    return toRecords(handshake(), fragmentLength);
  }

  static ByteBuf toRecords(byte[] handshake, int fragmentLength) {
    ByteBuf out = Unpooled.buffer();
    int pos = 0;
    while (pos < handshake.length) {
      int len = Math.min(fragmentLength, handshake.length - pos);
      out.writeByte(ClientHelloParser.RECORD_TYPE_HANDSHAKE);
      out.writeShort(0x0301);
      out.writeShort(len);
      out.writeBytes(handshake, pos, len);
      pos += len;
    }
    return out;
  }

  static byte[] uint16List(int... values) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeShort(values.length * 2);
    for (int v : values) {
      buf.writeShort(v);
    }
    return bytes(buf);
  }

  static byte[] bytes(ByteBuf buf) {
    byte[] bytes = new byte[buf.readableBytes()];
    buf.readBytes(bytes);
    buf.release();
    return bytes;
  }

  /// Drives a client side [io.netty.handler.ssl.SslHandler] far enough to produce a ClientHello and
  /// takes the bytes it writes. The JDK offers TLS 1.3 and 1.2, sends a server name and offers the
  /// ALPN protocols `h2` and `http/1.1`.
  ///
  /// @return the bytes as written to the network, possibly more than one TLS record
  static ByteBuf captureJdkClientHello() throws Exception {
    SslContext context =
        SslContextBuilder.forClient()
            .sslProvider(SslProvider.JDK)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocolConfig(
                new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1))
            .build();
    EmbeddedChannel channel =
        new EmbeddedChannel(context.newHandler(ByteBufAllocator.DEFAULT, "example.com", 443));
    try {
      // the handshake starts as soon as the channel is active, the first thing written out is the
      // ClientHello, possibly spread over several buffers
      ByteBuf collected = Unpooled.buffer();
      ByteBuf out;
      while ((out = channel.readOutbound()) != null) {
        collected.writeBytes(out);
        out.release();
      }
      if (collected.readableBytes() == 0) {
        throw new IllegalStateException("no ClientHello was written");
      }
      return collected;
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
