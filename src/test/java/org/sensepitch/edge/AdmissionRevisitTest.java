package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
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

/// Drives the challenge and answer endpoints with the cookies retained by a browser. An admitted
/// request is answered by a stand-in upstream. Every response is logged in the JSON request log,
/// which shows the admission history.
class AdmissionRevisitTest {
  private static final String TOKEN = Deflector.TOKEN_COOKIE_NAME;
  private static final String HISTORY = "admission0";
  private static final String LOGGED_TOKEN = SensepitchLogFields.ADMISSION_TOKEN;
  private static final String LOGGED_ORIGINAL = SensepitchLogFields.ADMISSION_ORIGINAL_TOKEN;
  private static final String LOGGED_REVISITS = SensepitchLogFields.ADMISSION_REVISITS;
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
  private final RequestLogCapture requestLog = new RequestLogCapture();
  private final EmbeddedChannel channel =
      new EmbeddedChannel(
          new RequestLoggingHandler(new ProxyMetrics(), new JsonRequestLogger(requestLog)),
          new ProtectionHandler(
              new DeflectorHandler(
                  new Deflector(config, new ConfigFiles(), () -> now), new ProxyMetrics())),
          new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
              if (msg instanceof LastHttpContent) {
                ctx.writeAndFlush(
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
              }
            }
          });

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

  @Test
  void requestLogShowsTheHistoryWhereItIsIssuedAndPresented() throws Exception {
    request("/", 403);
    assertNoHistoryLogged(lastLog());
    String first = answer();
    requestAdmitted();
    assertThat(lastLog().get(LOGGED_TOKEN).asText()).isEqualTo(first);
    assertNoHistoryLogged(lastLog());
    expire(first);
    assertThat(lastLog().has(LOGGED_TOKEN)).isFalse();
    assertHistoryLogged(lastLog(), first, 1);
    String second = answer();
    assertThat(lastLog().get(LOGGED_TOKEN).asText()).isEqualTo(second);
    assertHistoryLogged(lastLog(), first, 1);
    requestAdmitted();
    assertThat(lastLog().get(LOGGED_TOKEN).asText()).isEqualTo(second);
    assertHistoryLogged(lastLog(), first, 1);
    expire(second);
    assertHistoryLogged(lastLog(), first, 2);
    request("/", 403);
    assertHistoryLogged(lastLog(), first, 2);
  }

  @Test
  void answerWithExpiredAdmissionLogsTheHistoryItStarts() throws Exception {
    request("/", 403);
    String token = generator.newAdmission();
    cookies.put(TOKEN, new DefaultCookie(TOKEN, token));
    now = generator.checkAdmission(token) + config.admissionMaxAgeMillis() + 1;
    answer();
    assertHistoryLogged(lastLog(), token, 1);
  }

  /// A history whose signature does not verify is neither logged nor continued, while the
  /// admission itself still passes.
  @ParameterizedTest
  @ValueSource(strings = {"tampered", "wrongSecret", "retiredKey", "malformed"})
  void unverifiedHistoryIsNotLogged(String kind) throws Exception {
    request("/", 403);
    String first = answer();
    expire(first);
    String history = value(HISTORY);
    String second = answer();
    String unverified =
        switch (kind) {
          case "tampered" -> history.replace(".1.", ".27.");
          case "wrongSecret" ->
              signedHistory(new DefaultAdmissionTokenGenerator(new byte[4], 'A', "other"));
          case "retiredKey" ->
              signedHistory(new DefaultAdmissionTokenGenerator(new byte[4], 'B', SECRET));
          default -> "malformed";
        };
    cookies.put(HISTORY, new DefaultCookie(HISTORY, unverified));
    requestAdmitted();
    assertThat(lastLog().get(LOGGED_TOKEN).asText()).isEqualTo(second);
    assertNoHistoryLogged(lastLog());
    expire(second);
    assertHistoryLogged(lastLog(), second, 1);
  }

  /// A history cookie with a valid signature by the given generator, as another node or an
  /// earlier configuration would have issued it.
  private static String signedHistory(DefaultAdmissionTokenGenerator signer) {
    String payload = signer.newAdmission() + ".5";
    return payload + "." + signer.signHistory(payload);
  }

  /// Requests a page with the current admission while it is fresh, the stand-in upstream answers.
  private void requestAdmitted() {
    now = generator.checkAdmission(value(TOKEN));
    request("/", 200);
  }

  private JsonNode lastLog() throws Exception {
    return new ObjectMapper().readTree(requestLog.lines().getLast());
  }

  private static void assertHistoryLogged(JsonNode log, String original, long revisits) {
    assertThat(log.get(LOGGED_ORIGINAL).asText()).isEqualTo(original);
    assertThat(log.get(LOGGED_REVISITS).isIntegralNumber()).isTrue();
    assertThat(log.get(LOGGED_REVISITS).asLong()).isEqualTo(revisits);
  }

  private static void assertNoHistoryLogged(JsonNode log) {
    assertThat(log.has(LOGGED_ORIGINAL)).isFalse();
    assertThat(log.has(LOGGED_REVISITS)).isFalse();
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
