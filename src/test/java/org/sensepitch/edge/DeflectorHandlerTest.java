package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
public class DeflectorHandlerTest {

  private ProtectionHandler handler;
  private final ProxyMetrics metrics = new ProxyMetrics();
  private Channel channel;
  private HttpRequest lastRequest;
  private boolean passed;
  private Object messageWritten;
  static final String TWITTER = "Twitterbot/1.0";
  static final String WHATS_APP = "WhatsApp/3.0.0.0 A";

  @Test
  public void test() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request("/default");
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
    assertThat(trafficType()).isEqualTo("unknown");
    request("/default", WHATS_APP);
    assertThat(passed).isTrue();
    assertThat(trafficType()).isEqualTo("bot");
    request("/bypass");
    assertThat(passed).isTrue();
    assertThat(trafficType()).isEqualTo("unknown");
    request("/bypass/excluded");
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
    request("/default/twitter-bot-does-pass", TWITTER);
    assertThat(passed).isTrue();
  }

  /// @throws Exception
  @Test
  public void testWhatsAppImagePreview() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request("/default", WHATS_APP);
    assertThat(passed).isTrue();
    request("/neverbypass", WHATS_APP);
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
  }

  @Test
  public void testChallengeStepNoCache() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request(Deflector.CHALLENGE_STEP_URL);
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(204);
              assertThat(response.headers().get("Cache-Control"))
                  .isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
              assertThat(response.headers().get("Pragma")).isEqualTo("no-cache");
              assertThat(response.headers().get("Expires")).isEqualTo("0");
            });
  }

  @Test
  public void testChallengeResourcesNoCache() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request(Deflector.CHALLENGE_RESOURCES_URL + "/style.css");
    assertThat(passed).isFalse();
    expectResponseIsNoCacheOk("text/css; charset=utf-8");
    request(Deflector.CHALLENGE_RESOURCES_URL + "/script.js");
    assertThat(passed).isFalse();
    expectResponseIsNoCacheOk("text/javascript; charset=utf-8");
  }

  @Test
  public void testChallengeSolveAndReceiveAccessCookie() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request("/default");
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(403);
            });
    HttpResponse challengeResponse = (HttpResponse) messageWritten;
    String challenge = findCookieValue(challengeResponse, Deflector.CHALLENGE_COOKIE_NAME);
    assertThat(challenge).isNotNull();
    String nonce = findNonce(challenge, cfg.hashTargetPrefix(), cfg.powMaxIterations());
    DefaultHttpRequest answer =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            Deflector.CHALLENGE_ANSWER_URL + "?challenge=" + challenge + "&nonce=" + nonce);
    request(answer);
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(200);
            });
    HttpResponse answerResponse = (HttpResponse) messageWritten;
    String token = findCookieValue(answerResponse, Deflector.TOKEN_COOKIE_NAME);
    assertThat(token).isNotNull();
    assertThat(answer.headers().get(Deflector.ADMISSION_TOKEN_HEADER))
        .as("issued token is logged")
        .isEqualTo(token);
    assertThat(trafficType()).isEqualTo("human");
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/default");
    String cookieHeader =
        ServerCookieEncoder.STRICT.encode(new DefaultCookie(Deflector.TOKEN_COOKIE_NAME, token));
    req.headers().set(HttpHeaderNames.COOKIE, cookieHeader);
    request(req);
    assertThat(passed).isTrue();
    assertThat(trafficType()).isEqualTo("human");
  }

  private String trafficType() {
    return lastRequest.headers().get(Deflector.TRAFFIC_TYPE_HEADER);
  }

  /// Each outcome is counted once, challenge resources and progress steps are not counted.
  @Test
  public void countsOutcomes() throws Exception {
    DeflectorConfig cfg = getDeflectorConfig();
    init(cfg);
    request("/default");
    String challenge =
        findCookieValue((HttpResponse) messageWritten, Deflector.CHALLENGE_COOKIE_NAME);
    request(Deflector.CHALLENGE_RESOURCES_URL + "/script.js");
    request(Deflector.CHALLENGE_STEP_URL);
    request(Deflector.CHALLENGE_ANSWER_URL + "?challenge=garbage&nonce=0");
    request(Deflector.CHALLENGE_ANSWER_URL + "?nonce=1");
    String nonce = findNonce(challenge, cfg.hashTargetPrefix(), cfg.powMaxIterations());
    request(Deflector.CHALLENGE_ANSWER_URL + "?challenge=" + challenge + "&nonce=" + nonce);
    String token = findCookieValue((HttpResponse) messageWritten, Deflector.TOKEN_COOKIE_NAME);
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/default");
    req.headers().set(HttpHeaderNames.COOKIE, Deflector.TOKEN_COOKIE_NAME + "=" + token);
    request(req);
    request("/bypass");
    request("/default", TWITTER);
    PrometheusRegistry registry = new PrometheusRegistry();
    metrics.registerCollectors(registry::register);
    var snapshots = new MetricsAndMemoryTest.Snapshots(registry.scrape());
    Map<String, Double> expected =
        Map.of(
            "challenge_sent", 1.0,
            "answer_rejected", 2.0,
            "answer_accepted", 1.0,
            "passed", 1.0,
            "bypassed", 2.0);
    expected.forEach(
        (outcome, count) ->
            assertThat(
                    snapshots
                        .counter(
                            "sensepitch_deflector_requests",
                            "ingress",
                            "example.com",
                            "outcome",
                            outcome)
                        .getValue())
                .as(outcome)
                .isEqualTo(count));
  }

  /// The answer endpoint is reachable by anyone, malformed requests are refused.
  @Test
  public void challengeAnswerWithMissingParameters() throws Exception {
    init(getDeflectorConfig());
    for (String query : List.of("", "?challenge=abc", "?nonce=1", "?challenge=&nonce=")) {
      request(Deflector.CHALLENGE_ANSWER_URL + query);
      expectStatus(400);
    }
  }

  @Test
  public void challengeAnswerWithInvalidChallenge() throws Exception {
    init(getDeflectorConfig());
    request(Deflector.CHALLENGE_ANSWER_URL + "?challenge=garbage&nonce=0");
    expectStatus(400);
    assertThat(passed).isFalse();
  }

  @Test
  public void challengeResourceErrors() throws Exception {
    init(getDeflectorConfig());
    request(Deflector.CHALLENGE_RESOURCES_URL + "/");
    expectStatus(400);
    request(Deflector.CHALLENGE_RESOURCES_URL + "/does-not-exist.js");
    expectStatus(404);
  }

  /// Anything but a valid admission token leads to the challenge.
  @Test
  public void invalidAdmissionCookies() throws Exception {
    init(getDeflectorConfig());
    for (String token : List.of("", "Zunknown-prefix", "Xforged")) {
      DefaultHttpRequest req =
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/default");
      req.headers().set(HttpHeaderNames.COOKIE, Deflector.TOKEN_COOKIE_NAME + "=" + token);
      request(req);
      assertThat(passed).as("token '" + token + "'").isFalse();
      expectResponseIsChallenge();
    }
  }

  /// URI bypass and crawler detection are both active when both are configured.
  @Test
  public void bypassAndDetectCrawlerCombined() throws Exception {
    init(
        getDeflectorConfig().toBuilder()
            .detectCrawler(DetectCrawlerConfig.builder().build())
            .build());
    request("/bypass");
    assertThat(passed).isTrue();
    request("/default", TWITTER);
    assertThat(passed).isTrue();
    request("/default");
    assertThat(passed).isFalse();
  }

  @Test
  public void serverIpv4AddressMustBeIpv4() {
    assertThatThrownBy(
            () -> new Deflector(getDeflectorConfig().toBuilder().serverIpv4Address("::1").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Deflector(
                    getDeflectorConfig().toBuilder()
                        .serverIpv4Address("does-not-exist.invalid")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void expectStatus(int code) {
    assertThat(messageWritten)
        .isInstanceOfSatisfying(
            HttpResponse.class, response -> assertThat(response.status().code()).isEqualTo(code));
  }

  private static DeflectorConfig getDeflectorConfig() {
    DeflectorConfig cfg =
        DeflectorConfig.builder()
            .hashTargetPrefix("8")
            .serverIpv4Address("127.0.0.1")
            .bypass(BypassConfig.builder().uris(List.of("/bypass")).build())
            .noBypass(
                NoBypassConfig.builder().uris(List.of("/neverbypass", "/bypass/excluded")).build())
            .tokenGenerators(
                List.of(AdmissionTokenGeneratorConfig.builder().secret("asdf").prefix("X").build()))
            .build();
    return cfg;
  }

  private void expectResponseIsChallenge() {
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(403);
            });
  }

  private void expectResponseIsNoCacheOk(String expectedContentType) {
    assertThat(messageWritten)
        .isNotNull()
        .isInstanceOfSatisfying(
            HttpResponse.class,
            response -> {
              assertThat(response.status().code()).isEqualTo(200);
              assertThat(response.headers().get("Cache-Control"))
                  .isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
              assertThat(response.headers().get("Pragma")).isEqualTo("no-cache");
              assertThat(response.headers().get("Expires")).isEqualTo("0");
              assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                  .isEqualTo(expectedContentType);
              assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                  .isGreaterThan(0);
            });
  }

  private String findCookieValue(HttpResponse response, String cookieName) {
    for (String header : response.headers().getAll(HttpHeaderNames.SET_COOKIE)) {
      for (Cookie cookie : ServerCookieDecoder.STRICT.decode(header)) {
        if (cookie.name().equals(cookieName)) {
          return cookie.value();
        }
      }
    }
    return null;
  }

  private String findNonce(String challenge, String targetPrefix, int maxIterations) {
    int limit = maxIterations > 0 ? maxIterations : DeflectorConfig.DEFAULT_POW_MAX_ITERATIONS;
    for (long nonce = 0; nonce < limit; nonce++) {
      String hash = sha256Hex(challenge + nonce);
      if (hash.startsWith(targetPrefix)) {
        return Long.toString(nonce);
      }
    }
    throw new IllegalStateException("nonce not found for challenge");
  }

  private String sha256Hex(String value) {
    byte[] hash =
        ChallengeGenerationAndVerification.sha256(value.getBytes(StandardCharsets.ISO_8859_1));
    return HexFormat.of().formatHex(hash);
  }

  private void init(DeflectorConfig cfg) {
    ChannelOutboundHandler out =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
              throws Exception {
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            super.channelRead(ctx, msg);
          }
        };
    handler = new ProtectionHandler(new DeflectorHandler(new Deflector(cfg), metrics));
    channel = new EmbeddedChannel(out, handler, in);
  }

  void request(String uri, String userAgent) throws Exception {
    final DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    req.headers().add("User-Agent", userAgent);
    request(req);
  }

  void request(String uri) throws Exception {
    request(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri));
  }

  void request(HttpRequest req) throws Exception {
    lastRequest = req;
    ChannelHandlerContext ctx = channel.pipeline().context(ProtectionHandler.class);
    messageWritten = null;
    passed = false;
    if (!req.headers().contains(HttpHeaderNames.HOST)) {
      req.headers().set(HttpHeaderNames.HOST, "example.com");
    }
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
