package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class DeflectorHandlerTest {

  private ProtectionHandler handler;
  private Channel channel;
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
    request("/default", WHATS_APP);
    assertThat(passed).isTrue();
    request("/bypass");
    assertThat(passed).isTrue();
    request("/bypass/excluded");
    assertThat(passed).isFalse();
    expectResponseIsChallenge();
    request("/default/twitter-bot-does-pass", TWITTER);
    assertThat(passed).isTrue();
  }

  /**
   * @throws Exception
   */
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
    request(Deflector.CHALLENGE_ANSWER_URL + "?challenge=" + challenge + "&nonce=" + nonce);
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
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/default");
    String cookieHeader =
        ServerCookieEncoder.STRICT.encode(new DefaultCookie(Deflector.TOKEN_COOKIE_NAME, token));
    req.headers().set(HttpHeaderNames.COOKIE, cookieHeader);
    request(req);
    assertThat(passed).isTrue();
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
            // System.out.println("AdmissionHandler write " + msg);
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            // System.out.println("AdmissionHandler read " + msg);
            super.channelRead(ctx, msg);
          }
        };
    handler = new ProtectionHandler(new DeflectorHandler(new Deflector(cfg)));
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
    ChannelHandlerContext ctx = channel.pipeline().context(ProtectionHandler.class);
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
