package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/// The output of [GzipCompressor] is read by the JDK's [GZIPInputStream], the format a log server
/// decodes for `Content-Encoding: gzip`.
///
/// @author Jens Wilke
class GzipCompressorTest {

  static byte[] gunzip(GzipCompressor compressor) throws IOException {
    try (GZIPInputStream in =
        new GZIPInputStream(new ByteArrayInputStream(compressor.array(), 0, compressor.length()))) {
      return in.readAllBytes();
    }
  }

  static byte[] compressed(GzipCompressor compressor) {
    return Arrays.copyOf(compressor.array(), compressor.length());
  }

  @Test
  void partsRoundTrip() throws IOException {
    GzipCompressor compressor = new GzipCompressor(1024);
    byte[] first = "GET /index.html example.com ".repeat(100).getBytes(StandardCharsets.UTF_8);
    byte[] second = "and more".getBytes(StandardCharsets.UTF_8);
    compressor.write(first, 0, first.length);
    compressor.write(second, 2, 4);
    compressor.finish();
    assertThat(new String(gunzip(compressor), StandardCharsets.UTF_8))
        .isEqualTo("GET /index.html example.com ".repeat(100) + "d mo");
    assertThat(compressor.length()).as("repetitive records compress").isLessThan(200);
    compressor.end();
  }

  @Test
  void emptyBody() throws IOException {
    GzipCompressor compressor = new GzipCompressor(0);
    compressor.finish();
    assertThat(gunzip(compressor)).isEmpty();
  }

  /// A reused compressor writes the same bytes as a new one
  @Test
  void reuseAfterReset() {
    byte[] body = "the same body ".repeat(50).getBytes(StandardCharsets.UTF_8);
    GzipCompressor compressor = new GzipCompressor(64);
    compressor.write(body, 0, body.length);
    compressor.finish();
    byte[] first = compressed(compressor);
    compressor.reset();
    compressor.write(body, 0, body.length);
    compressor.finish();
    assertThat(compressed(compressor)).isEqualTo(first);
  }

  /// Random bytes do not compress, the buffer grows beyond its initial capacity and keeps it
  @Test
  void bufferGrowsForIncompressibleInput() throws IOException {
    byte[] random = new byte[100_000];
    new Random(42).nextBytes(random);
    GzipCompressor compressor = new GzipCompressor(1024);
    compressor.write(random, 0, random.length);
    compressor.finish();
    assertThat(compressor.length()).isGreaterThan(random.length);
    assertThat(gunzip(compressor)).isEqualTo(random);
    int capacity = compressor.array().length;
    compressor.reset();
    compressor.write(random, 0, random.length);
    compressor.finish();
    assertThat(compressor.array()).as("kept, not grown again").hasSize(capacity);
  }
}
