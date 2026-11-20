package org.sensepitch.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Writes a minimal IPv4 database in the MaxMind DB format, like the IPinfo database read by
/// [IpInfoCountryAndAsnLookup]: every address maps to one record `{country_code: ...}`. For tests
/// that need a database file without shipping one and without a writer library.
///
/// The layout follows the MaxMind DB format specification 2.0: a search tree of one node with
/// 24-bit records both pointing to the data record, 16 zero bytes, the data section, the metadata
/// marker and the metadata map.
///
/// @see <a href="https://maxmind.github.io/MaxMind-DB/">MaxMind DB File Format Specification</a>
/// @author Jens Wilke
final class TinyMmdb {

  private static final int NODE_COUNT = 1;
  private static final int RECORD_SIZE = 24;

  /// A record value above the node count points into the data section, at value - nodes - 16
  private static final int DATA_RECORD = NODE_COUNT + 16;

  private static final byte[] METADATA_MARKER = {
    (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'
  };

  // control byte types, the type in the upper three bits
  private static final int STRING = 2;
  private static final int UINT16 = 5;
  private static final int UINT32 = 6;
  private static final int MAP = 7;

  // extended types, control byte type 0 and the type minus 7 in the next byte
  private static final int UINT64 = 9;
  private static final int ARRAY = 11;

  private TinyMmdb() {}

  /// Writes a database that maps every IPv4 address to the country
  static void writeCountry(Path file, String countryCode) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // the only node: left and right record point to the data record
    record24(out, DATA_RECORD);
    record24(out, DATA_RECORD);
    out.write(new byte[16]);
    control(out, MAP, 1);
    string(out, "country_code");
    string(out, countryCode);
    out.write(METADATA_MARKER);
    control(out, MAP, 9);
    string(out, "node_count");
    control(out, UINT32, 1);
    out.write(NODE_COUNT);
    string(out, "record_size");
    control(out, UINT16, 1);
    out.write(RECORD_SIZE);
    string(out, "ip_version");
    control(out, UINT16, 1);
    out.write(4);
    string(out, "database_type");
    string(out, "test");
    string(out, "languages");
    extended(out, ARRAY, 1);
    string(out, "en");
    string(out, "binary_format_major_version");
    control(out, UINT16, 1);
    out.write(2);
    string(out, "binary_format_minor_version");
    control(out, UINT16, 0);
    string(out, "build_epoch");
    extended(out, UINT64, 0);
    string(out, "description");
    control(out, MAP, 1);
    string(out, "en");
    string(out, "test database");
    Files.write(file, out.toByteArray());
  }

  private static void record24(ByteArrayOutputStream out, int value) {
    out.write(value >> 16);
    out.write(value >> 8);
    out.write(value);
  }

  /// Control byte of a type with a size below 29, which needs no extra size bytes
  private static void control(ByteArrayOutputStream out, int type, int size) {
    out.write(type << 5 | size);
  }

  private static void extended(ByteArrayOutputStream out, int type, int size) {
    out.write(size);
    out.write(type - 7);
  }

  private static void string(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    control(out, STRING, bytes.length);
    out.writeBytes(bytes);
  }
}
