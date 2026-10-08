package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;

/// Extracts the fields needed for a JA4 fingerprint from the raw bytes a client sends before the
/// TLS handshake is processed. Only reads, never modifies the reader index of the passed buffer.
///
/// <p>The parser is intentionally forgiving: anything that is not a complete and well formed
/// ClientHello yields {@code null}, since a fingerprint is a nice to have and must never break a
/// connection.
///
/// @see <a href="https://github.com/FoxIO-LLC/ja4">JA4 specification</a>
public class ClientHelloParser {

  static final int RECORD_TYPE_HANDSHAKE = 0x16;
  static final int HANDSHAKE_TYPE_CLIENT_HELLO = 0x01;
  static final int RECORD_HEADER_LENGTH = 5;

  static final int EXT_SERVER_NAME = 0x0000;
  static final int EXT_SIGNATURE_ALGORITHMS = 0x000d;
  static final int EXT_ALPN = 0x0010;
  static final int EXT_SUPPORTED_VERSIONS = 0x002b;

  private static final int[] NO_VALUES = new int[0];

  /// @param in buffer holding everything received from the client so far, starting at the first
  ///   byte of the first TLS record
  /// @return the parsed ClientHello or {@code null} if the buffer does not contain a complete or a
  ///   valid ClientHello
  public static ClientHelloInfo parse(ByteBuf in) {
    try {
      ByteBuf handshake = reassembleHandshake(in);
      if (handshake == null) {
        return null;
      }
      return parseClientHello(handshake);
    } catch (IndexOutOfBoundsException | IllegalArgumentException ignore) {
      // truncated or malformed, no fingerprint
      return null;
    }
  }

  /// Concatenates the payload of all complete handshake records. A ClientHello is allowed to span
  /// multiple records, so the record layer needs to be stripped before the handshake message can be
  /// read.
  ///
  /// @return the handshake bytes available so far or {@code null} if this is not a handshake
  static ByteBuf reassembleHandshake(ByteBuf in) {
    int idx = in.readerIndex();
    int end = in.writerIndex();
    ByteBuf handshake = Unpooled.buffer();
    while (idx + RECORD_HEADER_LENGTH <= end) {
      if (in.getUnsignedByte(idx) != RECORD_TYPE_HANDSHAKE) {
        return null;
      }
      int length = in.getUnsignedShort(idx + 3);
      if (idx + RECORD_HEADER_LENGTH + length > end) {
        // record not received completely, use what we have
        break;
      }
      handshake.writeBytes(in, idx + RECORD_HEADER_LENGTH, length);
      idx += RECORD_HEADER_LENGTH + length;
    }
    return handshake.readableBytes() == 0 ? null : handshake;
  }

  static ClientHelloInfo parseClientHello(ByteBuf buf) {
    if (buf.readableBytes() < 4) {
      return null;
    }
    if (buf.readUnsignedByte() != HANDSHAKE_TYPE_CLIENT_HELLO) {
      return null;
    }
    int length = buf.readUnsignedMedium();
    if (buf.readableBytes() < length) {
      // handshake message not complete yet
      return null;
    }
    int version = buf.readUnsignedShort();
    buf.skipBytes(32); // random
    buf.skipBytes(buf.readUnsignedByte()); // legacy session id
    int[] ciphers = readUint16List(buf, buf.readUnsignedShort());
    buf.skipBytes(buf.readUnsignedByte()); // compression methods
    boolean sni = false;
    String alpn = null;
    int[] signatureAlgorithms = NO_VALUES;
    int[] extensions = NO_VALUES;
    if (buf.readableBytes() >= 2) {
      int extensionsEnd = buf.readUnsignedShort() + buf.readerIndex();
      int[] collected = new int[64];
      int count = 0;
      while (buf.readerIndex() + 4 <= extensionsEnd) {
        int type = buf.readUnsignedShort();
        int extensionEnd = buf.readUnsignedShort() + buf.readerIndex();
        if (!isGrease(type)) {
          if (count == collected.length) {
            collected = grow(collected);
          }
          collected[count++] = type;
          switch (type) {
            case EXT_SERVER_NAME -> sni = true;
            case EXT_ALPN -> alpn = readFirstAlpnProtocol(buf);
            case EXT_SUPPORTED_VERSIONS -> version = readHighestVersion(buf, version);
            case EXT_SIGNATURE_ALGORITHMS ->
                signatureAlgorithms = readUint16List(buf, buf.readUnsignedShort());
            default -> {}
          }
        }
        buf.readerIndex(extensionEnd);
      }
      extensions = trim(collected, count);
    }
    return new ClientHelloInfo(version, sni, ciphers, extensions, signatureAlgorithms, alpn);
  }

  /// Reads a list of 16 bit values of the given byte length, skipping GREASE values.
  static int[] readUint16List(ByteBuf buf, int byteLength) {
    int[] values = new int[byteLength / 2];
    int count = 0;
    int end = buf.readerIndex() + byteLength;
    while (buf.readerIndex() + 2 <= end) {
      int value = buf.readUnsignedShort();
      if (!isGrease(value)) {
        values[count++] = value;
      }
    }
    buf.readerIndex(end);
    return trim(values, count);
  }

  /// The supported_versions extension carries the actual version in TLS 1.3, the version in the
  /// handshake header is pinned to TLS 1.2 for compatibility.
  ///
  /// @return highest offered version or {@code fallback} if the extension holds GREASE only
  static int readHighestVersion(ByteBuf buf, int fallback) {
    int end = buf.readUnsignedByte() + buf.readerIndex();
    int highest = -1;
    while (buf.readerIndex() + 2 <= end) {
      int version = buf.readUnsignedShort();
      if (!isGrease(version) && version > highest) {
        highest = version;
      }
    }
    return highest < 0 ? fallback : highest;
  }

  /// @return the first offered protocol or {@code null} if the extension carries no protocol
  static String readFirstAlpnProtocol(ByteBuf buf) {
    int end = buf.readUnsignedShort() + buf.readerIndex();
    if (buf.readerIndex() + 1 > end) {
      return null;
    }
    int length = buf.readUnsignedByte();
    if (length == 0 || buf.readerIndex() + length > end) {
      return null;
    }
    // one byte maps to one char, so non ASCII protocol names can be hex encoded later on
    return buf.readCharSequence(length, StandardCharsets.ISO_8859_1).toString();
  }

  /// GREASE values are sent by clients to detect servers that break on unknown values, see RFC
  /// 8701. They are random per connection and need to be ignored to get a stable fingerprint.
  static boolean isGrease(int value) {
    return (value & 0x0f0f) == 0x0a0a && (value >> 8) == (value & 0xff);
  }

  static int[] grow(int[] values) {
    int[] grown = new int[values.length * 2];
    System.arraycopy(values, 0, grown, 0, values.length);
    return grown;
  }

  static int[] trim(int[] values, int count) {
    if (count == values.length) {
      return values;
    }
    int[] trimmed = new int[count];
    System.arraycopy(values, 0, trimmed, 0, count);
    return trimmed;
  }
}
