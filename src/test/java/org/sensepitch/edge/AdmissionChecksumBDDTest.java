package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.annotations.Steps;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Compatibility and malformed-input checks for admission checksum validation, and the creation
/// time returned for a valid token.
@ExtendWith(SerenityJUnit5Extension.class)
class AdmissionChecksumBDDTest {

  @Steps ChecksumSteps steps;

  @Test
  void existingEncoderRemainsCompatible() {
    steps.then_tokens_from_the_existing_encoder_are_valid_for_seeds(256);
  }

  @Test
  void changedChecksumDigitsAreRejected() {
    steps
        .given_a_token_from_the_existing_encoder(0)
        .then_every_changed_checksum_digit_is_rejected();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", ":", "@", "[", "`", "{", "é", "Ａ", "\u0000"})
  void nonAlphabetCharactersAreRejected(String invalid) {
    steps
        .given_a_token_from_the_existing_encoder(0)
        .then_character_is_rejected_at_every_checksum_position(invalid);
  }

  @Test
  void excessHighBitsCannotAliasAValidChecksum() {
    steps
        .given_a_token_from_the_existing_encoder(0)
        .then_checksum_with_excess_high_bits_is_rejected();
  }

  /// Times whose bits do not overlap `MILLIS_START_TIME - 1000` (0, `0x01000000`) were rejected by
  /// a precedence error, times encoded with `A` (10) or `a` (36) were decoded wrongly.
  @ParameterizedTest
  @ValueSource(longs = {0, 10, 36, 123, 0x01000000, 0xffffffffL})
  void creationTimeIsReturned(long seconds) {
    steps
        .given_a_token_from_the_existing_encoder(0, seconds)
        .then_creation_time_is(seconds * 1000 + MILLIS_START_TIME);
  }

  public static class ChecksumSteps {
    private static final String ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final DefaultAdmissionTokenGenerator generator =
        new DefaultAdmissionTokenGenerator(new byte[4], 'X', "secret");
    private String token;
    private byte[] hash;

    @Step
    public ChecksumSteps given_a_token_from_the_existing_encoder(int seed) {
      return given_a_token_from_the_existing_encoder(seed, 123);
    }

    @Step
    public ChecksumSteps given_a_token_from_the_existing_encoder(int seed, long seconds) {
      createToken(seed, seconds);
      return this;
    }

    /// Sets [#token] and [#hash]. Not a step, so a step checking many tokens stays one step:
    /// Serenity analyses the stack trace on every step call, 256 tokens as given/then steps
    /// took more than a second.
    private void createToken(int seed, long seconds) {
      byte[] mixed = ByteBuffer.allocate(MIXED_BYTES).putInt(seed).array();
      String uniqueId = calculateTimeChars(seconds) + encodeUniqueId(mixed);
      hash =
          ChallengeGenerationAndVerification.sha256(
              (uniqueId + "secret").getBytes(StandardCharsets.ISO_8859_1));
      token = 'X' + uniqueId + encodeChecksum(hash);
    }

    @Step
    public ChecksumSteps then_tokens_from_the_existing_encoder_are_valid_for_seeds(int seeds) {
      for (int seed = 0; seed < seeds; seed++) {
        createToken(seed, 123);
        assertThat(generator.checkAdmission(token)).as("token %s", token).isGreaterThan(0);
      }
      return this;
    }

    @Step
    public ChecksumSteps then_creation_time_is(long millis) {
      assertThat(generator.checkAdmission(token)).as("token %s", token).isEqualTo(millis);
      return this;
    }

    @Step
    public ChecksumSteps then_every_changed_checksum_digit_is_rejected() {
      for (int i = TOKEN_CHARS - CHECKSUM_CHARS; i < TOKEN_CHARS; i++) {
        for (char c : ALPHABET.toCharArray()) {
          if (c != token.charAt(i)) {
            assertThat(generator.checkAdmission(replaceCharacter(i, c))).isZero();
          }
        }
      }
      return this;
    }

    /// Build a tampered token to check that each checksum position is validated.
    private String replaceCharacter(int index, char c) {
      return token.substring(0, index) + c + token.substring(index + 1);
    }

    @Step
    public ChecksumSteps then_character_is_rejected_at_every_checksum_position(String invalid) {
      for (int i = TOKEN_CHARS - CHECKSUM_CHARS; i < TOKEN_CHARS; i++) {
        assertThat(generator.checkAdmission(replaceCharacter(i, invalid.charAt(0)))).isZero();
      }
      return this;
    }

    @Step
    public ChecksumSteps then_checksum_with_excess_high_bits_is_rejected() {
      long checksum = Integer.toUnsignedLong(ByteBuffer.wrap(hash).getInt());
      byte[] oversized = ByteBuffer.allocate(Long.BYTES).putLong(checksum + (1L << 32)).array();
      String encoded = encode(oversized, 0, oversized.length, ENCODING_RADIX, CHECKSUM_CHARS);
      assertThat(encoded).hasSize(CHECKSUM_CHARS);
      String tampered = token.substring(0, TOKEN_CHARS - CHECKSUM_CHARS) + encoded;
      assertThat(generator.checkAdmission(tampered)).isZero();
      return this;
    }
  }
}
