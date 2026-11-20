package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Drives the challenge and answer endpoints with the cookies retained by a browser.
class AdmissionRevisitTest {
  private static final String TOKEN = Deflector.TOKEN_COOKIE_NAME;
  private static final String HISTORY = "admission0";
  private static final String SECRET = "secret";
  private final Map<String, Cookie> cookies = new HashMap<>();
  private final DefaultAdmissionTokenGenerator generator =
      new DefaultAdmissionTokenGenerator(new byte[4], 'A', SECRET);
  private long now;
  private final DeflectorConfig config =
      DeflectorConfig.builder()
          .serverIpv4Address("127.0.0.1")
          .hashTargetPrefix("")
          .tokenGenerators(
              List.of(
                  new AdmissionTokenGeneratorConfig(
                      "A", SecretConfig.builder().value(SECRET).build())))
          .build();
  private final EmbeddedChannel channel =
      new EmbeddedChannel(
          new ProtectionHandler(
              new DeflectorHandler(
                  new Deflector(config, new ConfigFiles(), () -> now), new ProxyMetrics())));

  @AfterEach
  void close() {
    channel.finishAndReleaseAll();
  }

  @Test
  void twoExpiriesKeepOriginalTokenAcrossFreshAdmissions() {
    request("/", 403);
    assertThat(cookies).doesNotContainKey(HISTORY);
    String first = answer();
    expire(first);
    String history = value(HISTORY);
    assertThat(history).startsWith(first + ".1.");
    request("/", 403);
    assertThat(value(HISTORY)).isEqualTo(history);
    String second = answer();
    assertThat(second).isNotEqualTo(first);
    assertThat(value(HISTORY)).isEqualTo(history);
    expire(second);
    history = value(HISTORY);
    assertThat(history).startsWith(first + ".2.");
    assertThat(answer()).isNotEqualTo(second);
    assertThat(value(HISTORY)).isEqualTo(history);
  }

  @Test
  void answerReplacesAnAdmissionThatExpiredAfterChallengeIssue() {
    request("/", 403);
    String token = generator.newAdmission();
    cookies.put(TOKEN, new DefaultCookie(TOKEN, token));
    now = generator.checkAdmission(token) + config.admissionMaxAgeMillis() + 1;
    assertThat(answer()).isNotEqualTo(token);
    assertThat(value(HISTORY)).startsWith(token + ".1.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"token", "count", "malformed"})
  void tamperedHistoryRestartsAtCurrentExpiredToken(String field) {
    request("/", 403);
    String first = answer();
    expire(first);
    String history = value(HISTORY);
    String second = answer();
    String tampered =
        switch (field) {
          case "token" -> generator.newAdmission() + history.substring(first.length());
          case "count" -> history.replace(".1.", ".27.");
          default -> "malformed";
        };
    cookies.put(HISTORY, new DefaultCookie(HISTORY, tampered));
    expire(second);
    assertThat(value(HISTORY)).startsWith(second + ".1.");
  }

  @Test
  void futureAndUnauthenticatedTokensDoNotStartHistory() {
    String token = generator.newAdmission();
    now = generator.checkAdmission(token) - config.admissionClockSkewMillis() - 1;
    cookies.put(TOKEN, new DefaultCookie(TOKEN, token));
    request("/", 403);
    assertThat(cookies).doesNotContainKey(HISTORY);
    cookies.put(TOKEN, new DefaultCookie(TOKEN, "Ainvalid"));
    request("/", 403);
    assertThat(cookies).doesNotContainKey(HISTORY);
  }

  private void expire(String token) {
    now = generator.checkAdmission(token) + config.admissionMaxAgeMillis() + 1;
    request("/", 403);
    assertThat(cookies).doesNotContainKey(TOKEN);
    assertThat(cookies.get(HISTORY).isHttpOnly()).isTrue();
    assertThat(cookies.get(HISTORY).isSecure()).isTrue();
    assertThat(cookies.get(HISTORY).path()).isEqualTo("/");
  }

  private String answer() {
    request(
        Deflector.CHALLENGE_ANSWER_URL
            + "?challenge="
            + value(Deflector.CHALLENGE_COOKIE_NAME)
            + "&nonce=0",
        200);
    return value(TOKEN);
  }

  private String value(String name) {
    return cookies.get(name).value();
  }

  private void request(String uri, int status) {
    var req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    if (!cookies.isEmpty()) {
      req.headers()
          .set(HttpHeaderNames.COOKIE, ClientCookieEncoder.STRICT.encode(cookies.values()));
    }
    channel.writeInbound(req, LastHttpContent.EMPTY_LAST_CONTENT);
    FullHttpResponse response = channel.readOutbound();
    try {
      assertThat(response.status().code()).isEqualTo(status);
      for (String header : response.headers().getAll(HttpHeaderNames.SET_COOKIE)) {
        Cookie cookie = ClientCookieDecoder.STRICT.decode(header);
        if (cookie.maxAge() == 0) {
          cookies.remove(cookie.name());
        } else {
          cookies.put(cookie.name(), cookie);
        }
      }
    } finally {
      response.release();
    }
  }
}
