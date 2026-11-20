package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
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
    GzipCompressor compressor = new GzipCompressor(1024, Deflater.BEST_SPEED);
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
    GzipCompressor compressor = new GzipCompressor(0, Deflater.BEST_SPEED);
    compressor.finish();
    assertThat(gunzip(compressor)).isEmpty();
  }

  /// A reused compressor writes the same bytes as a new one
  @Test
  void reuseAfterReset() {
    byte[] body = "the same body ".repeat(50).getBytes(StandardCharsets.UTF_8);
    GzipCompressor compressor = new GzipCompressor(64, Deflater.BEST_SPEED);
    compressor.write(body, 0, body.length);
    compressor.finish();
    byte[] first = compressed(compressor);
    compressor.reset();
    compressor.write(body, 0, body.length);
    compressor.finish();
    assertThat(compressed(compressor)).isEqualTo(first);
  }

  /// The level trades CPU for bytes on the wire, it never changes the body
  @Test
  void levelChangesTheSizeNotTheBody() throws IOException {
    byte[] body =
        ("{\"http.request.method\":\"GET\",\"server.address\":\"www.example.com\","
                + "\"url.path\":\"/products/1234\",\"user_agent.original\":\"Mozilla/5.0 "
                + "(X11; Linux x86_64) AppleWebKit/537.36 Chrome/141.0 Safari/537.36\"}\n")
            .repeat(4096)
            .getBytes(StandardCharsets.UTF_8);
    GzipCompressor fast = new GzipCompressor(1024, Deflater.BEST_SPEED);
    GzipCompressor best = new GzipCompressor(1024, Deflater.BEST_COMPRESSION);
    fast.write(body, 0, body.length);
    fast.finish();
    best.write(body, 0, body.length);
    best.finish();
    assertThat(gunzip(fast)).isEqualTo(body);
    assertThat(gunzip(best)).isEqualTo(body);
    assertThat(best.length()).as("best compression").isLessThan(fast.length());
    fast.end();
    best.end();
  }

  /// Random bytes do not compress, the buffer grows beyond its initial capacity and keeps it
  @Test
  void bufferGrowsForIncompressibleInput() throws IOException {
    byte[] random = new byte[100_000];
    new Random(42).nextBytes(random);
    GzipCompressor compressor = new GzipCompressor(1024, Deflater.BEST_SPEED);
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
