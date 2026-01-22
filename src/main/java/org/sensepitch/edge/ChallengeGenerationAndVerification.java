package org.sensepitch.edge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Generate a challenge and verify the response with the generated nonce.
 *
 * @author Jens Wilke
 */
public class ChallengeGenerationAndVerification {

  private final ChallengeGenerator challengeGenerator;
  private final String targetPrefix;

  public ChallengeGenerationAndVerification(
    ChallengeGenerator challengeGenerator, String targetPrefix) {
    Objects.requireNonNull(targetPrefix);
    this.challengeGenerator = challengeGenerator;
    this.targetPrefix = targetPrefix;
  }

  public String getTargetPrefix() {
    return targetPrefix;
  }

  public String generateChallenge() {
    return challengeGenerator.generateChallenge();
  }

  /**
   * Check whether the challenge was created recently and the nonce fits.
   *
   * @return 0 if invalid or time in milliseconds the challenge was created
   * @see ChallengeGenerator#verifyChallenge(String)
   */
  public long verifyChallengeResponse(String challenge, String nonce) {
    long t = challengeGenerator.verifyChallenge(challenge);
    if (t > 0) {
      String hex = calculateSha256(challenge + nonce);
      if (hex.startsWith(targetPrefix)) {
        return t;
      }
    }
    return 0;
  }

  private static String calculateSha256(String msg) {
    byte[] ba = sha256((msg.getBytes(StandardCharsets.ISO_8859_1)));
    return HexFormat.of().formatHex(ba);
  }

  public static byte[] sha256(byte[] input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new UnsatisfiedLinkError(e.getMessage());
    }
  }

}
