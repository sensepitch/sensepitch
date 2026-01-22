package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public interface ChallengeGenerator {

  /**
   * Generate a challenge string that needs to be solved. A challenge should always contain
   * a time information and
   */
  String generateChallenge();

  /**
   * Verifies that the challenge was created by us and recently, so it is not possible to work with
   * a static response for a recorded challenge. The implementation just uses a millisecond
   * timestamp. As side effect the verification extracts the time the challenge was created. We can
   * use that to record the delay and there for the difficulty.
   *
   * @return 0, if not valid, greater 0 - if valid, timestamp in millis extracted from the challenge
   */
  long verifyChallenge(String challenge);
}
