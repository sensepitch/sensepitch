package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.MILLIS_START_TIME;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.MIXED_BYTES;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.calculateTimeChars;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.encodeChecksum;
import static org.sensepitch.edge.DefaultAdmissionTokenGenerator.encodeUniqueId;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Server-side expiry of granted admission tokens. Tokens are built with a controlled creation
/// time (the [AdmissionChecksumBDDTest] pattern) and checked against a supplied `nowMillis`, so
/// the age and clock-skew boundaries are exact without real time.
class DeflectorAdmissionExpiryTest {

  private static final String SECRET = "secret";
  private static final long NOW_SECONDS = 123_456_789L;
  private static final long CREATION_MILLIS = NOW_SECONDS * 1000 + MILLIS_START_TIME;
  private static final long MAX_AGE = DeflectorConfig.DEFAULT_ADMISSION_MAX_AGE_MILLIS;
  private static final long SKEW = DeflectorConfig.DEFAULT_ADMISSION_CLOCK_SKEW_MILLIS;

  @Test
  void builderAppliesDefaults() {
    DeflectorConfig defaults = DeflectorConfig.builder().build();
    assertThat(defaults.admissionMaxAgeMillis()).isEqualTo(MAX_AGE);
    assertThat(defaults.admissionClockSkewMillis()).isEqualTo(SKEW);
  }

  @Test
  void admissionMaxAgeMustBePositive() {
    assertThatThrownBy(() -> DeflectorConfig.builder().admissionMaxAgeMillis(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("admissionMaxAgeMillis must be positive");
    assertThatThrownBy(() -> DeflectorConfig.builder().admissionMaxAgeMillis(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("admissionMaxAgeMillis must be positive");
  }

  @Test
  void admissionClockSkewMustNotBeNegative() {
    assertThatThrownBy(() -> DeflectorConfig.builder().admissionClockSkewMillis(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("admissionClockSkewMillis must not be negative");
  }

  @Test
  void noCookieIsRejected() {
    Deflector deflector = newDeflector();
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    assertThat(deflector.checkAdmissionCookie(request, CREATION_MILLIS).accepted()).isFalse();
    assertThat(request.headers().contains(Deflector.ADMISSION_TOKEN_HEADER)).isFalse();
  }

  @Test
  void otherCookieIsRejected() {
    Deflector deflector = newDeflector();
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().set(HttpHeaderNames.COOKIE, "other=value");
    assertThat(deflector.checkAdmissionCookie(request, CREATION_MILLIS).accepted()).isFalse();
    assertThat(request.headers().contains(Deflector.ADMISSION_TOKEN_HEADER)).isFalse();
  }

  @Test
  void emptyTokenIsRejected() {
    Deflector deflector = newDeflector();
    assertRejected(deflector, "", CREATION_MILLIS);
  }

  @Test
  void unknownPrefixIsRejected() {
    Deflector deflector = newDeflector();
    assertRejected(deflector, "Z", CREATION_MILLIS);
  }

  @Test
  void malformedTokenIsRejected() {
    Deflector deflector = newDeflector();
    assertRejected(deflector, "Xforged", CREATION_MILLIS);
  }

  @Test
  void recentAdmissionIsAccepted() {
    Deflector deflector = newDeflector();
    assertAccepted(deflector, createToken(NOW_SECONDS), CREATION_MILLIS + 1_000);
  }

  @Test
  void tokenAtMaxAgeBoundaryIsAccepted() {
    Deflector deflector = newDeflector();
    assertAccepted(deflector, createToken(NOW_SECONDS), CREATION_MILLIS + MAX_AGE);
  }

  @Test
  void tokenJustPastMaxAgeIsRejected() {
    Deflector deflector = newDeflector();
    assertRejected(deflector, createToken(NOW_SECONDS), CREATION_MILLIS + MAX_AGE + 1);
  }

  @Test
  void tokenAtSkewBoundaryIsAccepted() {
    Deflector deflector = newDeflector();
    assertAccepted(deflector, createToken(NOW_SECONDS), CREATION_MILLIS - SKEW);
  }

  @Test
  void tokenTooFarInFutureIsRejected() {
    Deflector deflector = newDeflector();
    assertRejected(deflector, createToken(NOW_SECONDS), CREATION_MILLIS - SKEW - 1);
  }

  private static Deflector newDeflector() {
    return new Deflector(
        DeflectorConfig.builder()
            .serverIpv4Address("127.0.0.1")
            .tokenGenerators(
                List.of(
                    AdmissionTokenGeneratorConfig.builder()
                        .prefix("X")
                        .secret(SecretConfig.builder().value(SECRET).build())
                        .build()))
            .build(),
        new ConfigFiles());
  }

  private static HttpRequest requestWithCookie(String cookieValue) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().set(HttpHeaderNames.COOKIE, Deflector.TOKEN_COOKIE_NAME + "=" + cookieValue);
    return request;
  }

  private static void assertAccepted(Deflector deflector, String token, long nowMillis) {
    HttpRequest request = requestWithCookie(token);
    assertThat(deflector.checkAdmissionCookie(request, nowMillis).accepted()).isTrue();
    assertThat(request.headers().get(Deflector.ADMISSION_TOKEN_HEADER)).isEqualTo(token);
  }

  private static void assertRejected(Deflector deflector, String cookieValue, long nowMillis) {
    HttpRequest request = requestWithCookie(cookieValue);
    assertThat(deflector.checkAdmissionCookie(request, nowMillis).accepted()).isFalse();
    assertThat(request.headers().contains(Deflector.ADMISSION_TOKEN_HEADER)).isFalse();
  }

  /// Builds a token with the given 32-bit seconds value, like [AdmissionChecksumBDDTest].
  private static String createToken(long seconds) {
    byte[] mixed = ByteBuffer.allocate(MIXED_BYTES).putInt(0).array();
    String uniqueId = calculateTimeChars(seconds) + encodeUniqueId(mixed);
    byte[] hash =
        ChallengeGenerationAndVerification.sha256(
            (uniqueId + SECRET).getBytes(StandardCharsets.ISO_8859_1));
    return 'X' + uniqueId + encodeChecksum(hash);
  }
}
