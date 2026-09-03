package org.sensepitch.edge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/// JA4 TLS client fingerprint, e.g. {@code t13d1516h2_8daaf6152771_02713d6af862}.
///
/// <p>The fingerprint identifies the TLS library and its configuration, not the client, so
/// different clients using the same library share a fingerprint. It is stable across connections of
/// the same client as long as it is not reconfigured or updated.
///
/// @param value the fingerprint, three parts separated by {@code _}: a readable part, the hashed
///   cipher list and the hashed extension and signature algorithm list
/// @param raw the same fingerprint with the two hashes replaced by the lists they are built from
/// @see <a href="https://github.com/FoxIO-LLC/ja4">JA4 specification</a>
public record Ja4Fingerprint(String value, String raw) {

  /// Used when a list is empty, a hash of the empty string would be misleading.
  static final String NO_HASH = "000000000000";

  static final int HASH_LENGTH = 12;

  /// TLS over TCP, other transports would be {@code q} for QUIC and {@code d} for DTLS.
  static final char PROTOCOL_TCP = 't';

  public static Ja4Fingerprint of(ClientHelloInfo hello) {
    int[] ciphers = hello.ciphers().clone();
    Arrays.sort(ciphers);
    // server name and ALPN are left out so the fingerprint of a client does not change
    // when it connects to a different host or negotiates a different protocol
    int[] extensions =
        Arrays.stream(hello.extensions())
            .filter(
                type ->
                    type != ClientHelloParser.EXT_SERVER_NAME && type != ClientHelloParser.EXT_ALPN)
            .sorted()
            .toArray();
    String cipherList = hexList(ciphers);
    String extensionList = hexList(extensions);
    String signatureList = hexList(hello.signatureAlgorithms());
    String readable = readablePart(hello);
    String extensionHashInput =
        signatureList.isEmpty() ? extensionList : extensionList + "_" + signatureList;
    return new Ja4Fingerprint(
        readable + "_" + truncatedHash(cipherList) + "_" + truncatedHash(extensionHashInput),
        readable + "_" + cipherList + "_" + extensionList + "_" + signatureList);
  }

  /// First ten characters, e.g. {@code t13d1516h2}: transport, TLS version, whether a server name
  /// is sent, number of ciphers, number of extensions and the ALPN protocol.
  static String readablePart(ClientHelloInfo hello) {
    return ""
        + PROTOCOL_TCP
        + versionCode(hello.version())
        + (hello.sni() ? 'd' : 'i')
        + twoDigits(hello.ciphers().length)
        + twoDigits(hello.extensions().length)
        + alpnCode(hello.alpn());
  }

  static String versionCode(int version) {
    return switch (version) {
      case 0x0304 -> "13";
      case 0x0303 -> "12";
      case 0x0302 -> "11";
      case 0x0301 -> "10";
      case 0x0300 -> "s3";
      case 0x0002 -> "s2";
      case 0xfeff -> "d1";
      case 0xfefd -> "d2";
      case 0xfefc -> "d3";
      default -> "00";
    };
  }

  /// First and last character of the first offered protocol, e.g. {@code h2} or {@code h1} for
  /// {@code http/1.1}. Protocol names containing non alphanumeric characters are hex encoded, so
  /// they cannot be confused with a regular protocol name.
  static String alpnCode(String alpn) {
    if (alpn == null || alpn.isEmpty()) {
      return "00";
    }
    char first = alpn.charAt(0);
    char last = alpn.charAt(alpn.length() - 1);
    if (isAlphaNumeric(first) && isAlphaNumeric(last)) {
      return "" + first + last;
    }
    // e.g. the bytes 0xab 0xcd become "ad"
    return "" + hexDigits(first).charAt(0) + hexDigits(last).charAt(1);
  }

  static String hexDigits(char value) {
    return String.format("%02x", (int) value & 0xff);
  }

  static boolean isAlphaNumeric(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /// Counts above 99 are truncated, they would not fit into the fixed length fingerprint.
  static String twoDigits(int count) {
    return count > 99 ? "99" : String.format("%02d", count);
  }

  static String hexList(int[] values) {
    StringBuilder builder = new StringBuilder();
    for (int value : values) {
      if (!builder.isEmpty()) {
        builder.append(',');
      }
      builder.append(String.format("%04x", value));
    }
    return builder.toString();
  }

  static String truncatedHash(String input) {
    if (input.isEmpty()) {
      return NO_HASH;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, HASH_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
