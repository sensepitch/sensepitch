package org.sensepitch.edge;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Jens Wilke
 */
public class DefaultAdmissionTokenGenerator implements AdmissionTokenGenerator {

  private static final AtomicLong NEXT_THREAD_ID = new AtomicLong(0);

  private static final ThreadLocal<Long> THREAD_ID =
      ThreadLocal.withInitial(NEXT_THREAD_ID::getAndIncrement);

  private static final ThreadLocal<Sequence> TL_SEQUENCE = ThreadLocal.withInitial(Sequence::new);

  private static class Sequence {
    private long count = 0;

    public long nextLong() {
      return count++;
    }
  }

  static long getNextRandom() {
    return ThreadLocalRandom.current().nextLong();
  }

  private final String secret;
  private final char prefixChar;

  /**
   * The encoding number base used, which is 62 using alphanumeric chars only. Encoding base 64
   * would be more efficient since simple bit shifting can be used. However, efficiency of validity
   * check is most important, here the base does not matter much. Also, we want to avoid any special
   * characters these might always give trouble when we want to cut and past and search.
   */
  public static int ENCODING_RADIX = 62;

  public static final int SEQUENCE_BYTES = 2;
  public static final int TIME_BYTES = 4;
  public static final int TIME_CHARS = 6;
  public static final int RANDOM_BYTES = 5;
  public static int MIXED_BYTES = 12;

  public static long MILLIS_START_TIME = 1751509330799L;
  public static int MIXED_CHARS = 17;
  public static int CHECKSUM_BYTES = 4;
  public static int CHECKSUM_CHARS = 6;

  public static int TOKEN_CHARS = 30;

  private final byte[] serverIp4address;

  public DefaultAdmissionTokenGenerator(byte[] serverIpv4Address, char prefixChar, String secret) {
    this.prefixChar = prefixChar;
    this.secret = secret;
    this.serverIp4address = serverIpv4Address;
  }

  public long checkAdmission(String token) {
    if (token == null || token.length() != TOKEN_CHARS || token.charAt(0) != prefixChar) {
      return 0;
    }
    int offset = TIME_CHARS + MIXED_CHARS + 1;
    String uniqueId = token.substring(1, offset);
    String receivedChecksum = token.substring(offset, offset + CHECKSUM_CHARS);
    byte[] sha256 =
        ChallengeGenerationAndVerification.sha256(
            (uniqueId + secret).getBytes(StandardCharsets.ISO_8859_1));
    // TODO: performance instead of encoding the newly generated checksum, we can decode the
    // received checksum and compare the byte value
    String checkSum = encodeChecksum(sha256);
    if (!checkSum.equals(receivedChecksum)) {
      return 0;
    }
    return decodeLittleEndianToLong(token.substring(1, TIME_CHARS + 1), ENCODING_RADIX)
        & 0x0ffffffff * 1000 + MILLIS_START_TIME;
  }

  public static long calculateCurrentSecondsTime32bits() {
    return (System.currentTimeMillis() - MILLIS_START_TIME) / 1000 & 0xffffffffL;
  }

  static String calculateTimeChars(long time) {
    byte[] bytes = new byte[TIME_BYTES];
    int idx = TIME_BYTES;
    for (int i = 1; i <= TIME_BYTES; i++) {
      bytes[idx - i] = (byte) (time & 0xff);
      time >>>= 8;
    }
    return encodeTime(bytes);
  }

  public String newAdmission() {
    byte[] bytes = new byte[MIXED_BYTES];
    mergeMixed(
        bytes, THREAD_ID.get(), serverIp4address, TL_SEQUENCE.get().nextLong(), getNextRandom());
    String variationsChars = encodeUniqueId(bytes);
    String timeChars = calculateTimeChars(calculateCurrentSecondsTime32bits());
    String uniqueId = timeChars + variationsChars;
    byte[] sha256 =
        ChallengeGenerationAndVerification.sha256(
            (uniqueId + secret).getBytes(StandardCharsets.ISO_8859_1));
    String checkSum = encodeChecksum(sha256);
    return prefixChar + uniqueId + checkSum;
  }

  public static String encodeTime(byte[] bytes) {
    return encode(bytes, 0, TIME_BYTES, ENCODING_RADIX, TIME_CHARS);
  }

  public static String encodeUniqueId(byte[] bytes) {
    return encode(bytes, 0, MIXED_BYTES, ENCODING_RADIX, MIXED_CHARS);
  }

  public static String encodeChecksum(byte[] sha256) {
    return encode(sha256, 0, CHECKSUM_BYTES, ENCODING_RADIX, CHECKSUM_CHARS);
  }

  static long decodeLittleEndianToLong(String str, int radix) {
    long result = 0L;
    char[] chars = str.toCharArray();
    for (int i = chars.length - 1; i >= 0; i--) {
      result *= radix;
      int c = chars[i];
      int value;
      if (c > 'a') {
        value = c - 'a' + 36;
      } else if (c > 'A') {
        value = c - 'A' + 10;
      } else {
        value = c - '0';
      }
      result += value;
    }
    return result;
  }

  public static int mergeMixed(
      byte[] bytes, long threadId, byte[] serverIpv4Address, long sequence, long random) {
    int idx = 0;
    bytes[idx++] = (byte) threadId;
    bytes[idx++] = serverIpv4Address[0];
    bytes[idx++] = serverIpv4Address[1];
    bytes[idx++] = serverIpv4Address[2];
    bytes[idx++] = serverIpv4Address[3];
    for (int i = 0; i < SEQUENCE_BYTES; i++) {
      bytes[idx++] = (byte) (sequence & 0xff);
      sequence >>>= 8;
    }
    for (int i = 0; i < RANDOM_BYTES; i++) {
      bytes[idx++] = (byte) (random & 0xff);
      random >>>= 8;
    }
    return idx;
  }

  private static String pad(StringBuilder sb, int length) {
    for (int i = sb.length(); i < length; i++) {
      sb.append('0');
    }
    return sb.toString();
  }

  private static final char[] ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

  /**
   * Encode a byte[] to a Base62 string (no padding). Leading zero‐bytes will be preserved as
   * leading '0' chars if you pad externally.
   */
  public static String encode(byte[] input, int offset, int length, int base, int padToLength) {
    return pad(encode(input, offset, length, base), padToLength).toString();
  }

  public static StringBuilder encode(byte[] input, int offset, int length, int base) {
    BigInteger base0 = BigInteger.valueOf(base);
    // treat as unsigned
    BigInteger value = new BigInteger(1, input, offset, length);
    if (value.equals(BigInteger.ZERO)) {
      return new StringBuilder("0");
    }
    StringBuilder sb = new StringBuilder();
    while (value.signum() > 0) {
      BigInteger[] divRem = value.divideAndRemainder(base0);
      sb.append(ALPHABET[divRem[1].intValue()]);
      value = divRem[0];
    }
    return sb;
  }
}
