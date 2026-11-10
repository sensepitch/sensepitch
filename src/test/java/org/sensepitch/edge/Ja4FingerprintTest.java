package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// JA4 fingerprint construction, checked against the example of the JA4 specification.
///
/// @see <a href="https://github.com/FoxIO-LLC/ja4/blob/main/technical_details/JA4.md">JA4
///   technical details</a>
/// @author Jens Wilke
public class Ja4FingerprintTest {

  /// Chrome example of the JA4 specification
  static final String SPEC_JA4 = "t13d1516h2_8daaf6152771_e5627efa2ab1";

  static final String SPEC_JA4_RAW =
      "t13d1516h2_002f,0035,009c,009d,1301,1302,1303,c013,c014,c02b,c02c,c02f,c030,cca8,cca9"
          + "_0005,000a,000b,000d,0012,0015,0017,001b,0023,002b,002d,0033,4469,ff01"
          + "_0403,0804,0401,0503,0805,0501,0806,0601";

  /// Ciphers in the order sent, unsorted.
  static final int[] SPEC_CIPHERS = {
    0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc013, 0xc014, 0x009c,
    0x009d, 0x002f, 0x0035
  };

  static final int[] SPEC_SIGNATURE_ALGORITHMS = {
    0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601
  };

  /// A Chrome like ClientHello with the content of the specification example, plus GREASE values
  /// that need to be ignored.
  static ClientHelloBuilder specHello() {
    int grease = 0x3a3a;
    int[] ciphers = new int[SPEC_CIPHERS.length + 1];
    ciphers[0] = grease;
    System.arraycopy(SPEC_CIPHERS, 0, ciphers, 1, SPEC_CIPHERS.length);
    return new ClientHelloBuilder()
        .ciphers(ciphers)
        .extension(grease)
        .extension(0x001b)
        .sni("example.com")
        .extension(0x0033)
        .extension(0x0005)
        .alpn("h2", "http/1.1")
        .extension(0x0023)
        .signatureAlgorithms(SPEC_SIGNATURE_ALGORITHMS)
        .extension(0x0012)
        .extension(0x4469)
        .extension(0x000b)
        .extension(0xff01)
        .extension(0x002d)
        .supportedVersions(grease, 0x0304, 0x0303)
        .extension(0x000a)
        .extension(0x0017)
        .extension(0x0015);
  }

  @Test
  public void specificationExampleFromInfo() {
    ClientHelloInfo info =
        new ClientHelloInfo(
            0x0304,
            true,
            SPEC_CIPHERS,
            new int[] {
              0x001b, 0x0000, 0x0033, 0x0005, 0x0010, 0x0023, 0x000d, 0x0012, 0x4469, 0x000b,
              0xff01, 0x002d, 0x002b, 0x000a, 0x0017, 0x0015
            },
            SPEC_SIGNATURE_ALGORITHMS,
            "h2");
    Ja4Fingerprint fingerprint = Ja4Fingerprint.of(info);
    assertThat(fingerprint.value()).isEqualTo(SPEC_JA4);
    assertThat(fingerprint.raw()).isEqualTo(SPEC_JA4_RAW);
  }

  @Test
  public void specificationExampleFromBytes() {
    assertThat(Ja4Handler.compute(specHello().build()).value()).isEqualTo(SPEC_JA4);
    assertThat(Ja4Handler.compute(specHello().records(50)).value()).isEqualTo(SPEC_JA4);
  }

  @Test
  public void inputIsNotModified() {
    int[] ciphers = {0x1302, 0x1301};
    Ja4Fingerprint.of(new ClientHelloInfo(0x0304, false, ciphers, new int[0], new int[0], null));
    assertThat(ciphers).containsExactly(0x1302, 0x1301);
  }

  @Test
  public void emptyListsAreNotHashed() {
    Ja4Fingerprint fingerprint =
        Ja4Fingerprint.of(
            new ClientHelloInfo(0x0303, false, new int[0], new int[0], new int[0], null));
    assertThat(fingerprint.value()).isEqualTo("t12i000000_000000000000_000000000000");
  }

  /// Without signature algorithms the extension hash covers the extensions only, no trailing `_`.
  @Test
  public void noSignatureAlgorithms() {
    Ja4Fingerprint fingerprint =
        Ja4Fingerprint.of(
            new ClientHelloInfo(
                0x0303, false, new int[] {0x002f}, new int[] {0x000a}, new int[0], null));
    assertThat(fingerprint.value())
        .isEqualTo(
            "t12i010100_"
                + Ja4Fingerprint.truncatedHash("002f")
                + "_"
                + Ja4Fingerprint.truncatedHash("000a"));
  }

  /// SNI and ALPN are counted, but not part of the extension hash.
  @Test
  public void sniAndAlpnExcludedFromHash() {
    Ja4Fingerprint with =
        Ja4Fingerprint.of(
            new ClientHelloInfo(
                0x0304,
                true,
                new int[] {0x1301},
                new int[] {0x0000, 0x0010, 0x000a},
                new int[0],
                "h2"));
    assertThat(with.value()).startsWith("t13d0103h2_");
    assertThat(with.value()).endsWith("_" + Ja4Fingerprint.truncatedHash("000a"));
  }

  @Test
  public void countsAreCappedAt99() {
    int[] many = new int[120];
    for (int i = 0; i < many.length; i++) {
      many[i] = 0x1000 + i;
    }
    Ja4Fingerprint fingerprint =
        Ja4Fingerprint.of(new ClientHelloInfo(0x0304, false, many, many, new int[0], null));
    assertThat(fingerprint.value()).startsWith("t13i999900_");
  }

  @ParameterizedTest
  @CsvSource({
    "0x0304, 13",
    "0x0303, 12",
    "0x0302, 11",
    "0x0301, 10",
    "0x0300, s3",
    "0x0002, s2",
    "0xfeff, d1",
    "0xfefd, d2",
    "0xfefc, d3",
    "0x1234, 00"
  })
  public void versionCode(String version, String expected) {
    assertThat(Ja4Fingerprint.versionCode(Integer.decode(version))).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"h2, h2", "http/1.1, h1", "x, xx", "NULL, 00", "'', 00"},
      nullValues = "NULL")
  public void alpnCode(String alpn, String expected) {
    assertThat(Ja4Fingerprint.alpnCode(alpn)).isEqualTo(expected);
  }

  /// Non alphanumeric first or last character: first hex digit of the first byte and last hex digit
  /// of the last byte.
  @Test
  public void alpnCodeNonAlphanumeric() {
    assertThat(Ja4Fingerprint.alpnCode("«Í")).isEqualTo("ad");
    assertThat(Ja4Fingerprint.alpnCode("h/")).isEqualTo("6f");
    assertThat(Ja4Fingerprint.alpnCode("/h")).isEqualTo("28");
  }
}
