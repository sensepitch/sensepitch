package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Parsing of a real ClientHello sent by the JDK TLS implementation. This is an integration test,
/// since the result depends on the JDK and starting an `SSLEngine` is expensive. The parser itself
/// is covered by [ClientHelloParserTest].
///
/// @author Jens Wilke
public class ClientHelloParserIT {

  /// ClientHello of the JDK TLS implementation, captured once, since starting an `SSLEngine` is
  /// the expensive part of the tests using it
  static byte[] jdkHelloBytes;

  @BeforeAll
  static void captureJdkClientHello() throws Exception {
    ByteBuf hello = ClientHelloBuilder.captureJdkClientHello();
    try {
      jdkHelloBytes = new byte[hello.readableBytes()];
      hello.readBytes(jdkHelloBytes);
    } finally {
      hello.release();
    }
  }

  @Test
  public void jdkClientHello() {
    ClientHelloInfo info = ClientHelloParser.parse(Unpooled.wrappedBuffer(jdkHelloBytes));
    assertThat(info).isNotNull();
    assertThat(info.version()).isEqualTo(0x0304);
    assertThat(info.sni()).isTrue();
    assertThat(info.alpn()).isEqualTo("h2");
    assertThat(info.ciphers()).contains(0x1301);
    assertThat(info.signatureAlgorithms()).isNotEmpty();
  }

  /// Random corruption must never escape as an exception.
  @Test
  public void corruptedBytesNeverThrow() {
    byte[] bytes = jdkHelloBytes;
    Random random = new Random(4711);
    for (int i = 0; i < 20_000; i++) {
      byte[] copy = bytes.clone();
      int flips = 1 + random.nextInt(4);
      for (int j = 0; j < flips; j++) {
        // keep the record type, otherwise most runs stop at the first byte
        copy[1 + random.nextInt(copy.length - 1)] = (byte) random.nextInt(256);
      }
      ByteBuf buf = Unpooled.wrappedBuffer(copy, 0, random.nextInt(copy.length + 1));
      assertThatCode(() -> ClientHelloParser.parse(buf)).doesNotThrowAnyException();
    }
  }
}
