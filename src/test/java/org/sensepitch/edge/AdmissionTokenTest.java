package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class AdmissionTokenTest {

  static byte[] ZERO_IPV4 = new byte[] {0, 0, 0, 0};
  static byte[] ONE_IPV4 = new byte[] {1, 1, 1, 1};
  static byte[] ALL_FF_BYTES = getFfBytes(1000);
  DefaultAdmissionTokenGenerator admissionHandler =
      new DefaultAdmissionTokenGenerator(ONE_IPV4, 'X', "secret");

  @Test
  public void testMixed() {
    byte[] bytes = new byte[20];
    int length = mergeMixed(bytes, 0, ZERO_IPV4, 0, 0);
    assertThat(length).isEqualTo(MIXED_BYTES);
    String s = encode(ALL_FF_BYTES, 0, MIXED_BYTES, ENCODING_RADIX).toString();
    assertThat(s.length()).isEqualTo(MIXED_CHARS);
  }

  @Test
  public void testChecksum() {
    int length = encode(ALL_FF_BYTES, 0, CHECKSUM_BYTES, ENCODING_RADIX).length();
    assertThat(length).isEqualTo(CHECKSUM_CHARS);
  }

  @Test
  public void testTime() {
    String maxTime = calculateTimeChars(-1);
    assertThat(maxTime.length()).isEqualTo(TIME_CHARS);
    for (long time : new long[] {0, 123, 999, 0x7fffffff}) {
      String timeChars = calculateTimeChars(time);
      long decodedTime = decodeLittleEndianToLong(timeChars, ENCODING_RADIX);
      assertThat(decodedTime).isEqualTo(time);
    }
  }

  @Test
  public void testToken() {
    String token = admissionHandler.newAdmission();
    assertThat(token.length()).isEqualTo(TOKEN_CHARS);
  }

  static byte[] getFfBytes(int length) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) 0xff);
    return bytes;
  }

  // @Test
  public void printSequnce() {
    System.out.println(admissionHandler.newAdmission());
    System.out.println(admissionHandler.newAdmission());
    System.out.println(admissionHandler.newAdmission());
    System.out.println(admissionHandler.newAdmission());
    System.out.println(admissionHandler.newAdmission());
    System.out.println(admissionHandler.newAdmission());
  }

  @Test
  public void test1000Admissions() {
    for (int i = 0; i < 1000; i++) {
      admissionHandler.newAdmission();
    }
  }

  @Test
  public void testValid() {
    String token = admissionHandler.newAdmission();
    assertThat(admissionHandler.checkAdmission(token)).isGreaterThan(0);
    for (String another :
        new String[] {
          "XxzO000M2QihLfB9VLB46000UISkx1",
          "XxzO000Sk9MODlb6VLB46000A8Wn32",
          "XxzO000sj4B5ORlBVLB46000nsyFO3"
        }) {
      assertThat(admissionHandler.checkAdmission(another)).isGreaterThan(0);
    }
  }

  @Test
  public void testInvalid() {
    assertThat(admissionHandler.checkAdmission("")).isEqualTo(0L);
    assertThat(admissionHandler.checkAdmission("123")).isEqualTo(0L);
    assertThat(admissionHandler.checkAdmission("0123:123:123")).isEqualTo(0L);
    for (String another :
        new String[] {
          "XxzOs00M2QihLfB9VLB46000UISkx1",
          "XxzO000Sk9MODlb6VLB46000A8Wn3x",
          "XxzO000sj4B5ORlBVLB46000nsyFO9"
        }) {
      assertThat(admissionHandler.checkAdmission(another)).isEqualTo(0);
    }
  }
}
