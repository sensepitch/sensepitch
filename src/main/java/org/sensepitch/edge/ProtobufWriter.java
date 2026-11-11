package org.sensepitch.edge;

import java.util.Arrays;

/// Minimal append only Protocol Buffers encoder, enough to produce OTLP messages without a
/// dependency on the protobuf runtime.
///
/// Encoding does not allocate, except when the buffer grows, so a writer can be reused for many
/// messages:
///
/// - Nested messages are written in place: [#startMessage(int)] reserves the room for the length
///   prefix, [#endMessage(int)] writes the length and moves the content up by the unused bytes.
/// - Strings are UTF-8 encoded straight into the buffer, without a temporary byte array.
/// - [#truncate(int)] removes a partly written message, e.g. one that does not fit.
///
/// Not thread safe, a writer is used by one thread at a time.
///
/// @see <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers encoding</a>
/// @author Jens Wilke
public final class ProtobufWriter {

  static final int WIRE_VARINT = 0;
  static final int WIRE_FIXED64 = 1;
  static final int WIRE_LENGTH_DELIMITED = 2;
  static final int WIRE_FIXED32 = 5;

  /// Room reserved for the length of a nested message, enough for any `int`
  static final int LENGTH_RESERVE = 5;

  private byte[] buffer;
  private int size;

  public ProtobufWriter() {
    this(256);
  }

  public ProtobufWriter(int initialCapacity) {
    buffer = new byte[initialCapacity];
  }

  /// Number of bytes written so far
  public int size() {
    return size;
  }

  /// The internal buffer, valid up to [#size()]. Changes when the buffer grows.
  public byte[] array() {
    return buffer;
  }

  /// Copy of the encoded bytes
  public byte[] toByteArray() {
    return Arrays.copyOf(buffer, size);
  }

  /// Removes everything written, to reuse the buffer
  public void reset() {
    size = 0;
  }

  /// Removes everything written after the given size, e.g. a message that does not fit
  public void truncate(int newSize) {
    if (newSize < 0 || newSize > size) {
      throw new IllegalArgumentException("size " + newSize + " outside 0.." + size);
    }
    size = newSize;
  }

  /// Field of type `int64`, `int32`, `uint64`, `uint32` or `enum`. Negative values take ten bytes,
  /// as in the protobuf encoding of `int64`.
  public ProtobufWriter varint(int field, long value) {
    tag(field, WIRE_VARINT);
    rawVarint(value);
    return this;
  }

  /// Field of type `bool`
  public ProtobufWriter bool(int field, boolean value) {
    return varint(field, value ? 1 : 0);
  }

  /// Field of type `fixed64` or `sfixed64`
  public ProtobufWriter fixed64(int field, long value) {
    tag(field, WIRE_FIXED64);
    ensure(8);
    for (int i = 0; i < 8; i++) {
      buffer[size++] = (byte) (value >>> (8 * i));
    }
    return this;
  }

  /// Field of type `fixed32` or `sfixed32`
  public ProtobufWriter fixed32(int field, int value) {
    tag(field, WIRE_FIXED32);
    ensure(4);
    for (int i = 0; i < 4; i++) {
      buffer[size++] = (byte) (value >>> (8 * i));
    }
    return this;
  }

  /// Field of type `string`, UTF-8 encoded
  public ProtobufWriter string(int field, CharSequence value) {
    return string(field, value, 0, value.length());
  }

  /// Field of type `string` with the characters `start` (inclusive) to `end` (exclusive) of the
  /// value, UTF-8 encoded
  public ProtobufWriter string(int field, CharSequence value, int start, int end) {
    tag(field, WIRE_LENGTH_DELIMITED);
    int length = utf8Length(value, start, end);
    rawVarint(length);
    ensure(length);
    utf8(value, start, end);
    return this;
  }

  /// Field of type `bytes` with the value of a hex string, e.g. a trace id
  public ProtobufWriter hexBytes(int field, CharSequence hex) {
    if ((hex.length() & 1) != 0) {
      throw new IllegalArgumentException("odd number of hex digits");
    }
    tag(field, WIRE_LENGTH_DELIMITED);
    int length = hex.length() / 2;
    rawVarint(length);
    ensure(length);
    for (int i = 0; i < hex.length(); i += 2) {
      buffer[size++] = (byte) ((hexDigit(hex.charAt(i)) << 4) | hexDigit(hex.charAt(i + 1)));
    }
    return this;
  }

  /// Field of type `bytes`
  public ProtobufWriter bytes(int field, byte[] value) {
    return bytes(field, value, 0, value.length);
  }

  /// Field of type `bytes`, or an already encoded message
  public ProtobufWriter bytes(int field, byte[] value, int offset, int length) {
    tag(field, WIRE_LENGTH_DELIMITED);
    rawVarint(length);
    ensure(length);
    System.arraycopy(value, offset, buffer, size, length);
    size += length;
    return this;
  }

  /// Field holding a nested message, encoded by another writer
  public ProtobufWriter message(int field, ProtobufWriter message) {
    return bytes(field, message.buffer, 0, message.size);
  }

  /// Appends bytes that are already encoded, e.g. complete fields
  public ProtobufWriter raw(byte[] encoded) {
    ensure(encoded.length);
    System.arraycopy(encoded, 0, buffer, size, encoded.length);
    size += encoded.length;
    return this;
  }

  /// Tag and length of a length-delimited field whose content of `length` bytes is sent separately
  public ProtobufWriter lengthDelimited(int field, int length) {
    tag(field, WIRE_LENGTH_DELIMITED);
    rawVarint(length);
    return this;
  }

  /// Starts a nested message in place, its fields are written next.
  ///
  /// @return mark to pass to [#endMessage(int)]
  public int startMessage(int field) {
    tag(field, WIRE_LENGTH_DELIMITED);
    ensure(LENGTH_RESERVE);
    int mark = size;
    size += LENGTH_RESERVE;
    return mark;
  }

  /// Ends a nested message: writes its length and moves the content up by the unused room
  public void endMessage(int mark) {
    int contentStart = mark + LENGTH_RESERVE;
    int length = size - contentStart;
    int lengthBytes = varintSize(length);
    size = mark;
    rawVarint(length);
    if (lengthBytes < LENGTH_RESERVE) {
      System.arraycopy(buffer, contentStart, buffer, size, length);
    }
    size += length;
  }

  /// Number of bytes of a varint
  static int varintSize(long value) {
    int bytes = 1;
    while ((value & ~0x7FL) != 0) {
      value >>>= 7;
      bytes++;
    }
    return bytes;
  }

  /// Number of bytes of the characters in UTF-8. An unpaired surrogate is encoded as `?`, like
  /// [String#getBytes(java.nio.charset.Charset)] does.
  static int utf8Length(CharSequence value, int start, int end) {
    int length = 0;
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      if (c < 0x80) {
        length++;
      } else if (c < 0x800) {
        length += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < end
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        length += 4;
        i++;
      } else if (Character.isSurrogate(c)) {
        length++;
      } else {
        length += 3;
      }
    }
    return length;
  }

  private void utf8(CharSequence value, int start, int end) {
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      if (c < 0x80) {
        buffer[size++] = (byte) c;
      } else if (c < 0x800) {
        buffer[size++] = (byte) (0xC0 | (c >> 6));
        buffer[size++] = (byte) (0x80 | (c & 0x3F));
      } else if (Character.isHighSurrogate(c)
          && i + 1 < end
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        int codePoint = Character.toCodePoint(c, value.charAt(++i));
        buffer[size++] = (byte) (0xF0 | (codePoint >> 18));
        buffer[size++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
        buffer[size++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
        buffer[size++] = (byte) (0x80 | (codePoint & 0x3F));
      } else if (Character.isSurrogate(c)) {
        buffer[size++] = '?';
      } else {
        buffer[size++] = (byte) (0xE0 | (c >> 12));
        buffer[size++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        buffer[size++] = (byte) (0x80 | (c & 0x3F));
      }
    }
  }

  private static int hexDigit(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    throw new IllegalArgumentException("not a hex digit: " + c);
  }

  private void tag(int field, int wireType) {
    rawVarint(((long) field << 3) | wireType);
  }

  private void rawVarint(long value) {
    ensure(10);
    while ((value & ~0x7FL) != 0) {
      buffer[size++] = (byte) ((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    buffer[size++] = (byte) value;
  }

  private void ensure(int additional) {
    if (size + additional > buffer.length) {
      buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + additional));
    }
  }
}
