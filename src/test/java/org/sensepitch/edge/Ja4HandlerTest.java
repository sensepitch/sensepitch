package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// [Ja4Handler] sits in front of TLS termination, so it must pass on every byte unchanged and leave
/// the pipeline after the ClientHello, whatever arrives.
///
/// @author Jens Wilke
public class Ja4HandlerTest {

  EmbeddedChannel channel = new EmbeddedChannel(new Ja4Handler());

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  public void completeHelloInOneRead() {
    ByteBuf hello = Ja4FingerprintTest.specHello().build();
    byte[] sent = copy(hello);
    channel.writeInbound(hello);
    assertThat(Ja4Handler.lookup(channel).value()).isEqualTo(Ja4FingerprintTest.SPEC_JA4);
    assertThat(channel.pipeline().get(Ja4Handler.class)).isNull();
    assertPassedOn(sent);
  }

  @Test
  public void helloSpreadOverSeveralReads() {
    ByteBuf hello = Ja4FingerprintTest.specHello().records(40);
    byte[] sent = copy(hello);
    int chunk = 23;
    for (int pos = 0; pos < sent.length; pos += chunk) {
      assertThat(Ja4Handler.lookup(channel)).isNull();
      assertThat(channel.pipeline().get(Ja4Handler.class)).isNotNull();
      channel.writeInbound(Unpooled.wrappedBuffer(sent, pos, Math.min(chunk, sent.length - pos)));
    }
    hello.release();
    assertThat(Ja4Handler.lookup(channel).value()).isEqualTo(Ja4FingerprintTest.SPEC_JA4);
    assertThat(channel.pipeline().get(Ja4Handler.class)).isNull();
    assertPassedOn(sent);
  }

  /// A plain HTTP request on the TLS port is passed on, the TLS handler deals with it.
  @Test
  public void notTls() {
    byte[] sent = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    channel.writeInbound(Unpooled.wrappedBuffer(sent));
    assertThat(Ja4Handler.lookup(channel)).isNull();
    assertThat(channel.pipeline().get(Ja4Handler.class)).isNull();
    assertPassedOn(sent);
  }

  @Test
  public void emptyBufferIsIgnored() {
    channel.writeInbound(Unpooled.EMPTY_BUFFER);
    assertThat(channel.pipeline().get(Ja4Handler.class)).isNotNull();
  }

  /// Handshake records that never form a ClientHello must not be collected forever.
  @Test
  public void giveUpAfterMaximumSize() {
    byte[] record = new byte[1024 + 5];
    record[0] = ClientHelloParser.RECORD_TYPE_HANDSHAKE;
    record[1] = 3;
    record[3] = 0x04; // length 1024
    record[5] = 0x01; // ClientHello
    record[6] = 0x7f; // claims a length that never arrives
    int reads = 0;
    while (channel.pipeline().get(Ja4Handler.class) != null) {
      channel.writeInbound(Unpooled.wrappedBuffer(record));
      reads++;
      assertThat(reads).isLessThan(100);
    }
    assertThat(reads * record.length).isGreaterThan(Ja4Handler.MAX_CLIENT_HELLO_BYTES);
    assertThat(Ja4Handler.lookup(channel)).isNull();
  }

  @Test
  public void connectionClosedBeforeHelloComplete() {
    byte[] sent = copy(Ja4FingerprintTest.specHello().build());
    channel.writeInbound(Unpooled.wrappedBuffer(sent, 0, 10));
    channel.close();
    assertThat(Ja4Handler.lookup(channel)).isNull();
  }

  @Test
  public void headerHandlerSendsFingerprint() {
    channel.writeInbound(Ja4FingerprintTest.specHello().build());
    HttpRequest request = request();
    new EmbeddedChannelWithAttribute(channel, Ja4Config.builder().enable(true).build())
        .read(request);
    assertThat(Ja4HeaderHandler.extract(request)).isEqualTo(Ja4FingerprintTest.SPEC_JA4);
    assertThat(request.headers().get(Ja4HeaderHandler.JA4_RAW_HEADER)).isNull();
  }

  @Test
  public void headerHandlerSendsRawFingerprint() {
    channel.writeInbound(Ja4FingerprintTest.specHello().build());
    HttpRequest request = request();
    new EmbeddedChannelWithAttribute(channel, Ja4Config.builder().enable(true).raw(true).build())
        .read(request);
    assertThat(request.headers().get(Ja4HeaderHandler.JA4_RAW_HEADER))
        .isEqualTo(Ja4FingerprintTest.SPEC_JA4_RAW);
  }

  /// A client must not be able to send a fingerprint of its choosing to the upstream.
  @Test
  public void headerHandlerRemovesClientSuppliedHeaders() {
    HttpRequest request = request();
    request.headers().set(Ja4HeaderHandler.JA4_HEADER, "forged");
    request.headers().set(Ja4HeaderHandler.JA4_RAW_HEADER, "forged");
    new EmbeddedChannelWithAttribute(channel, Ja4Config.builder().enable(true).raw(true).build())
        .read(request);
    assertThat(request.headers().contains(Ja4HeaderHandler.JA4_HEADER)).isFalse();
    assertThat(request.headers().contains(Ja4HeaderHandler.JA4_RAW_HEADER)).isFalse();
  }

  static HttpRequest request() {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
  }

  /// Runs a [Ja4HeaderHandler] in a channel that carries the fingerprint of `source`.
  static class EmbeddedChannelWithAttribute {
    final EmbeddedChannel http;

    EmbeddedChannelWithAttribute(EmbeddedChannel source, Ja4Config config) {
      http = new EmbeddedChannel(new Ja4HeaderHandler(config));
      http.attr(Ja4Handler.ATTRIBUTE).set(Ja4Handler.lookup(source));
    }

    void read(HttpRequest request) {
      http.writeInbound(request);
      assertThat((Object) http.readInbound()).isSameAs(request);
      http.finishAndReleaseAll();
    }
  }

  private void assertPassedOn(byte[] expected) {
    ByteBuf collected = Unpooled.buffer();
    ByteBuf buf;
    while ((buf = channel.readInbound()) != null) {
      collected.writeBytes(buf);
      buf.release();
    }
    assertThat(copy(collected)).isEqualTo(expected);
  }

  private static byte[] copy(ByteBuf buf) {
    byte[] bytes = new byte[buf.readableBytes()];
    buf.getBytes(buf.readerIndex(), bytes);
    return bytes;
  }
}
