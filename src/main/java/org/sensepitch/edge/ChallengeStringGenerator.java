package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public interface ChallengeStringGenerator {

  String generateChallenge();

  /**
   * Verifies that the challenge was created by us and recently, so it is not possible to work with
   * a static response for a recorded challenge. The implementation just uses a millisecond
   * timestamp. As side effect the verification extracts the time the challenge was created. We can
   * use that to record the delay and there for the difficulty.
   *
   * @return 0, if not valid, greater 0 - time in millis if challenge is valid
   */
  long verifyChallenge(String challenge);
}
