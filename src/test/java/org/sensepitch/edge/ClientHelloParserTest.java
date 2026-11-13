package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/// Parsing of TLS ClientHello bytes, which arrive unauthenticated from the network.
///
/// @author Jens Wilke
public class ClientHelloParserTest {

  static final int GREASE_1 = 0x0a0a;
  static final int GREASE_2 = 0xdada;

  static ClientHelloBuilder typical() {
    return new ClientHelloBuilder()
        .ciphers(GREASE_1, 0x1301, 0x1302, 0xc02b)
        .extension(GREASE_2)
        .sni("example.com")
        .alpn("h2", "http/1.1")
        .supportedVersions(GREASE_1, 0x0304, 0x0303)
        .signatureAlgorithms(GREASE_1, 0x0403, 0x0804)
        .extension(0x0017);
  }

  @Test
  public void parseTypicalHelloAndSkipGrease() {
    ClientHelloInfo info = ClientHelloParser.parse(typical().build());
    assertThat(info).isNotNull();
    assertThat(info.version()).isEqualTo(0x0304);
    assertThat(info.sni()).isTrue();
    assertThat(info.alpn()).isEqualTo("h2");
    assertThat(info.ciphers()).containsExactly(0x1301, 0x1302, 0xc02b);
    assertThat(info.extensions()).containsExactly(0x0000, 0x0010, 0x002b, 0x000d, 0x0017);
    assertThat(info.signatureAlgorithms()).containsExactly(0x0403, 0x0804);
  }

  @Test
  public void legacyVersionWithoutSupportedVersions() {
    ClientHelloInfo info =
        ClientHelloParser.parse(
            new ClientHelloBuilder().legacyVersion(0x0302).ciphers(0x002f).build());
    assertThat(info.version()).isEqualTo(0x0302);
    assertThat(info.sni()).isFalse();
    assertThat(info.alpn()).isNull();
    assertThat(info.extensions()).isEmpty();
    assertThat(info.signatureAlgorithms()).isEmpty();
  }

  @Test
  public void supportedVersionsWithGreaseOnlyFallsBackToLegacyVersion() {
    ClientHelloInfo info =
        ClientHelloParser.parse(new ClientHelloBuilder().supportedVersions(GREASE_1).build());
    assertThat(info.version()).isEqualTo(0x0303);
  }

  @Test
  public void noExtensionBlock() {
    ClientHelloInfo info =
        ClientHelloParser.parse(
            new ClientHelloBuilder().ciphers(0x002f).noExtensionBlock().build());
    assertThat(info).isNotNull();
    assertThat(info.extensions()).isEmpty();
    assertThat(info.ciphers()).containsExactly(0x002f);
  }

  @Test
  public void alpnWithEmptyListOrEmptyProtocol() {
    ClientHelloInfo emptyList =
        ClientHelloParser.parse(
            new ClientHelloBuilder()
                .extension(ClientHelloParser.EXT_ALPN, new byte[] {0, 0})
                .build());
    assertThat(emptyList.alpn()).isNull();
    assertThat(emptyList.extensions()).containsExactly(ClientHelloParser.EXT_ALPN);
    ClientHelloInfo emptyProtocol =
        ClientHelloParser.parse(new ClientHelloBuilder().alpn("").build());
    assertThat(emptyProtocol.alpn()).isNull();
  }

  @Test
  public void manyExtensionsGrowTheCollectingArray() {
    ClientHelloBuilder builder = new ClientHelloBuilder();
    for (int i = 0; i < 150; i++) {
      builder.extension(0x1000 + i);
    }
    ClientHelloInfo info = ClientHelloParser.parse(builder.build());
    assertThat(info.extensions()).hasSize(150).startsWith(0x1000).endsWith(0x1000 + 149);
  }

  @Test
  public void helloSpreadOverSeveralRecords() {
    ClientHelloInfo single = ClientHelloParser.parse(typical().build());
    ClientHelloInfo split = ClientHelloParser.parse(typical().records(16));
    assertThat(split).usingRecursiveComparison().isEqualTo(single);
  }

  /// Every incomplete prefix of the bytes must yield no result and no exception, since the
  /// [Ja4Handler] tries to parse after each read.
  @Test
  public void everyTruncationYieldsNull() {
    for (ByteBuf complete : new ByteBuf[] {typical().build(), typical().records(16)}) {
      for (int len = 0; len < complete.readableBytes(); len++) {
        ByteBuf prefix = complete.slice(0, len);
        assertThat(ClientHelloParser.parse(prefix)).as("prefix length " + len).isNull();
      }
      assertThat(ClientHelloParser.parse(complete)).isNotNull();
    }
  }

  @Test
  public void notAHandshakeRecord() {
    ByteBuf buf = typical().build();
    buf.setByte(0, 0x17); // application data
    assertThat(ClientHelloParser.parse(buf)).isNull();
  }

  @Test
  public void notAClientHello() {
    byte[] handshake = typical().handshake();
    handshake[0] = 0x02; // ServerHello
    assertThat(ClientHelloParser.parse(ClientHelloBuilder.toRecords(handshake, 1000))).isNull();
  }

  @Test
  public void plainHttp() {
    ByteBuf buf = Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n".getBytes());
    assertThat(ClientHelloParser.parse(buf)).isNull();
  }

  /// A client may send more than the ClientHello in its first flight, e.g. a change_cipher_spec
  /// record and early data with TLS 1.3 0-RTT. The ClientHello is complete, so it is fingerprinted.
  @Test
  public void helloFollowedByOtherRecord() {
    ByteBuf buf = typical().build();
    buf.writeBytes(new byte[] {0x14, 0x03, 0x03, 0x00, 0x01, 0x01});
    assertThat(ClientHelloParser.parse(buf)).isNotNull();
  }

  @Test
  public void lengthFieldsBeyondTheMessageYieldNull() {
    byte[] handshake = new ClientHelloBuilder().ciphers(0x1301).handshake();
    // cipher list length is after type(1), length(3), version(2), random(32), session id(1+32)
    int cipherLengthPos = 1 + 3 + 2 + 32 + 1 + 32;
    handshake[cipherLengthPos] = (byte) 0x7f;
    assertThat(ClientHelloParser.parse(ClientHelloBuilder.toRecords(handshake, 1000))).isNull();
  }

  @Test
  public void isGrease() {
    assertThat(ClientHelloParser.isGrease(0x0a0a)).isTrue();
    assertThat(ClientHelloParser.isGrease(0xfafa)).isTrue();
    assertThat(ClientHelloParser.isGrease(0x0a1a)).isFalse();
    assertThat(ClientHelloParser.isGrease(0x1a0a)).isFalse();
    assertThat(ClientHelloParser.isGrease(0x1301)).isFalse();
  }
}
