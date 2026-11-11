package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/// Encoding examples from the Protocol Buffers documentation
///
/// @author Jens Wilke
class ProtobufWriterTest {

  static String hex(ProtobufWriter writer) {
    return HexFormat.of().formatHex(writer.toByteArray());
  }

  @Test
  void varint() {
    assertThat(hex(new ProtobufWriter().varint(1, 150))).isEqualTo("089601");
    assertThat(hex(new ProtobufWriter().varint(1, -1))).isEqualTo("08ffffffffffffffffff01");
    assertThat(hex(new ProtobufWriter().bool(2, true))).isEqualTo("1001");
  }

  @Test
  void stringAndNestedMessage() {
    assertThat(hex(new ProtobufWriter().string(2, "testing"))).isEqualTo("120774657374696e67");
    ProtobufWriter nested = new ProtobufWriter().varint(1, 150);
    assertThat(hex(new ProtobufWriter().message(3, nested))).isEqualTo("1a03089601");
  }

  @Test
  void fixedWidth() {
    assertThat(hex(new ProtobufWriter().fixed64(1, 1))).isEqualTo("090100000000000000");
    assertThat(hex(new ProtobufWriter().fixed32(1, 1))).isEqualTo("0d01000000");
  }

  @Test
  void growsAndResets() {
    ProtobufWriter writer = new ProtobufWriter(1);
    writer.string(1, "x".repeat(1000));
    assertThat(writer.size()).isEqualTo(1000 + 3);
    writer.reset();
    assertThat(writer.size()).isZero();
  }

  /// A nested message written in place equals one encoded by a separate writer, for short content
  /// (one length byte) and long content (several length bytes)
  @Test
  void nestedMessageInPlace() {
    for (int length : new int[] {0, 1, 127, 128, 300, 20_000}) {
      String content = "x".repeat(length);
      ProtobufWriter separate =
          new ProtobufWriter().message(3, new ProtobufWriter().string(1, content));
      ProtobufWriter inPlace = new ProtobufWriter(4);
      int mark = inPlace.startMessage(3);
      inPlace.string(1, content);
      inPlace.endMessage(mark);
      assertThat(inPlace.toByteArray()).as("length " + length).isEqualTo(separate.toByteArray());
    }
  }

  @Test
  void nestedTwice() {
    ProtobufWriter inPlace = new ProtobufWriter();
    int outer = inPlace.startMessage(1);
    int inner = inPlace.startMessage(2);
    inPlace.varint(1, 150);
    inPlace.endMessage(inner);
    inPlace.endMessage(outer);
    assertThat(hex(inPlace)).isEqualTo("0a05" + "1203089601");
  }

  /// UTF-8 is encoded like the JDK does, including surrogate pairs and unpaired surrogates
  @Test
  void utf8LikeTheJdk() {
    for (String value :
        new String[] {
          "plain", "äöü ß", "€ 中文", "emoji \uD83D\uDE00 end", "lone \uD83D x", "\uDE00"
        }) {
      ProtobufWriter writer = new ProtobufWriter().string(1, value);
      byte[] expected = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ProtobufWriter reference = new ProtobufWriter().bytes(1, expected);
      assertThat(writer.toByteArray()).as(value).isEqualTo(reference.toByteArray());
    }
  }

  @Test
  void stringRange() {
    assertThat(hex(new ProtobufWriter().string(2, "xxtestingyy", 2, 9)))
        .isEqualTo("120774657374696e67");
  }

  @Test
  void hexBytes() {
    assertThat(hex(new ProtobufWriter().hexBytes(1, "00f0Aa"))).isEqualTo("0a0300f0aa");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ProtobufWriter().hexBytes(1, "abc"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProtobufWriter().hexBytes(1, "zz"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncateAndRaw() {
    ProtobufWriter writer = new ProtobufWriter().varint(1, 150);
    int mark = writer.size();
    writer.string(2, "dropped");
    writer.truncate(mark);
    writer.raw(new byte[] {0x10, 0x01});
    assertThat(hex(writer)).isEqualTo("0896011001");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.truncate(100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lengthDelimitedHeader() {
    assertThat(hex(new ProtobufWriter().lengthDelimited(1, 300))).isEqualTo("0aac02");
  }
}
