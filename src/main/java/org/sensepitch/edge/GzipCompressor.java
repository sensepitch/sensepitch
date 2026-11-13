package org.sensepitch.edge;

import java.util.zip.CRC32;
import java.util.zip.Deflater;

/// Compresses the body of one request into gzip format, RFC 1952, reusable without allocating.
/// [java.util.zip.GZIPOutputStream] would allocate its deflater and buffers per request; this one
/// keeps them, see [#reset()]. The header and trailer are written by hand.
///
/// Holds native memory of the [Deflater], so [#end()] it when no longer used. Not thread safe, one
/// instance per request in flight, see [HttpBatchSender].
///
/// @author Jens Wilke
final class GzipCompressor {

  /// Magic, method deflate, no flags, no time, no extra flags, unknown operating system
  private static final byte[] HEADER = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};

  private static final int TRAILER_BYTES = 8;

  // the fastest level: the records compress well anyway, and the sending must keep up
  private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
  private final CRC32 crc = new CRC32();
  private byte[] out;
  private int length;

  /// @param initialCapacity expected compressed size, the buffer grows as needed and keeps its
  ///   size for the next request
  GzipCompressor(int initialCapacity) {
    out = new byte[Math.max(HEADER.length + TRAILER_BYTES, initialCapacity)];
    reset();
  }

  /// Starts a new body
  void reset() {
    deflater.reset();
    crc.reset();
    System.arraycopy(HEADER, 0, out, 0, HEADER.length);
    length = HEADER.length;
  }

  /// Compresses the bytes, a part of the body
  void write(byte[] bytes, int offset, int count) {
    crc.update(bytes, offset, count);
    deflater.setInput(bytes, offset, count);
    while (!deflater.needsInput()) {
      deflate();
    }
  }

  /// Completes the body, then [#array()] holds [#length()] bytes of it
  void finish() {
    deflater.finish();
    while (!deflater.finished()) {
      deflate();
    }
    ensureRoom(TRAILER_BYTES);
    writeIntLittleEndian((int) crc.getValue());
    // the size modulo 2^32, as specified
    writeIntLittleEndian((int) deflater.getBytesRead());
  }

  byte[] array() {
    return out;
  }

  int length() {
    return length;
  }

  /// Frees the native memory of the deflater
  void end() {
    deflater.end();
  }

  private void deflate() {
    ensureRoom(1);
    length += deflater.deflate(out, length, out.length - length);
  }

  private void ensureRoom(int bytes) {
    if (out.length - length < bytes) {
      byte[] larger = new byte[Math.max(out.length * 2, length + bytes)];
      System.arraycopy(out, 0, larger, 0, length);
      out = larger;
    }
  }

  private void writeIntLittleEndian(int value) {
    out[length++] = (byte) value;
    out[length++] = (byte) (value >>> 8);
    out[length++] = (byte) (value >>> 16);
    out[length++] = (byte) (value >>> 24);
  }
}
