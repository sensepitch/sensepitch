package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.sensepitch.edge.TimeBasedChallengeString.generateChallengeString;
import static org.sensepitch.edge.TimeBasedChallengeString.verifyChallengeString;

import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class ChallengeTest {

  @Test
  public void challengeTestValid() {
    String challenge = generateChallengeString();
    // System.out.println(challenge);
    assertThat(verifyChallengeString(challenge)).isNotEqualTo(0L);
  }

  @Test
  public void challengeTestInvalid() {
    assertThat(verifyChallengeString("")).isEqualTo(0L);
    assertThat(verifyChallengeString("123")).isEqualTo(0L);
    assertThat(verifyChallengeString("SDkjsdkC")).isEqualTo(0L);
    assertThat(verifyChallengeString("SDkjsdk0aoiewjfoiewjfC")).isEqualTo(0L);
  }

  ChallengeStringGenerator DUMMY_CHALLENGE_GENERATOR =
      new ChallengeStringGenerator() {
        @Override
        public String generateChallenge() {
          return "";
        }

        @Override
        public long verifyChallenge(String challenge) {
          return 1;
        }
      };

  @Test
  public void verifyExampleChallenge() {
    String challenge = "m849b8541";
    String nonce = "1658";
    ChallengeGenerationAndVerification challengeHandler =
        new ChallengeGenerationAndVerification(DUMMY_CHALLENGE_GENERATOR, "888");
    assertThat(challengeHandler.verifyChallengeParameters(challenge, nonce)).isEqualTo(1);
  }
}
