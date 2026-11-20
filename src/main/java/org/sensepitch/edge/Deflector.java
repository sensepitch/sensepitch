package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/// @author Jens Wilke
public class Deflector {

  private static final ProxyLogger LOG = ProxyLogger.get(DeflectorHandler.class);

  public static final String CHALLENGE_ANSWER_URL = "/.sensepitch.challenge.complete";
  public static final String CHALLENGE_STEP_URL = "/.sensepitch.challenge.step";
  public static final String CHALLENGE_RESOURCES_URL = "/.sensepitch.challenge.files";
  public static final String htmlTemplate = ResourceLoader.loadTextFile("challenge/challenge.html");
  public static final String TOKEN_COOKIE_NAME = "sensepitch-tk";
  public static final String CHALLENGE_COOKIE_NAME = "sensepitch-ch";
  private static final long COOKIE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60;
  private static final String HISTORY_COOKIE_NAME = "admission0";
  private static final ResourceFiles challengeFiles = new ResourceFiles("challenge/files/");

  /// Request header containing the validated or newly issued admission token
  public static final String ADMISSION_TOKEN_HEADER = SensepitchHeaders.PREFIX + "Admission-Token";

  /// Request header containing the original admission token of the verified `admission0` history
  /// cookie, the one presented or the one issued with the response. Absent without history.
  public static final String ADMISSION_ORIGINAL_TOKEN_HEADER =
      SensepitchHeaders.PREFIX + "Admission-Original-Token";

  /// Request header containing the revisit count of the history, see
  /// [#ADMISSION_ORIGINAL_TOKEN_HEADER]: how many expired admissions the history records.
  public static final String ADMISSION_REVISITS_HEADER =
      SensepitchHeaders.PREFIX + "Admission-Revisits";

  /// Request header telling whether the request is human or bot traffic: [#TRAFFIC_HUMAN],
  /// [#TRAFFIC_BOT] or [#TRAFFIC_UNKNOWN]. Absent if no protection handled the request.
  public static final String TRAFFIC_TYPE_HEADER = SensepitchHeaders.PREFIX + "Traffic-Type";

  /// Traffic type: valid admission cookie or accepted challenge answer
  public static final String TRAFFIC_HUMAN = "human";

  /// Traffic type: detected crawler
  public static final String TRAFFIC_BOT = "bot";

  /// Traffic type: not determined, e.g. a request of the challenge or a configured bypass
  public static final String TRAFFIC_UNKNOWN = "unknown";

  private final ChallengeGenerationAndVerification challengeVerification;
  private final NoBypassCheck noBypassCheck;
  private final BypassCheck bypassCheck;
  private final AdmissionTokenGenerator tokenGenerator;
  private final Map<Character, DefaultAdmissionTokenGenerator> tokenGenerators = new HashMap<>();
  private final int powMaxIterations;
  private final long admissionMaxAgeMillis;
  private final long admissionClockSkewMillis;

  private final LongSupplier admissionClock;

  /// @param files reads the secrets of the token generators, see [SecretConfig]
  Deflector(DeflectorConfig cfg, ConfigFiles files) {
    this(cfg, files, System::currentTimeMillis);
  }

  /// Uses a supplied clock for admission expiry checks.
  Deflector(DeflectorConfig cfg, ConfigFiles files, LongSupplier admissionClock) {
    this.admissionClock = admissionClock;
    challengeVerification =
        new ChallengeGenerationAndVerification(new TimeBasedChallenge(), cfg.hashTargetPrefix());
    powMaxIterations = cfg.powMaxIterations();
    admissionMaxAgeMillis = cfg.admissionMaxAgeMillis();
    admissionClockSkewMillis = cfg.admissionClockSkewMillis();
    if (cfg.noBypass() != null) {
      noBypassCheck = new DefaultNoBypassCheck(cfg.noBypass());
    } else {
      noBypassCheck = NoBypassCheck.FALSE;
    }
    BypassCheck buildBypass = BypassCheck.NO_BYPASS;
    if (cfg.bypass() != null) {
      buildBypass = new DefaultBypassCheck(cfg.bypass());
    }
    if (cfg.detectCrawler() != null) {
      buildBypass = chainBypassCheck(buildBypass, new DetectCrawler(cfg.detectCrawler()));
    }
    bypassCheck = buildBypass;
    byte[] serverIpv4Address;
    if (cfg.serverIpv4Address() != null) {
      try {
        serverIpv4Address = Inet4Address.getByName(cfg.serverIpv4Address()).getAddress();
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException(e);
      }
      if (serverIpv4Address.length != 4) {
        throw new IllegalArgumentException("IPv4 address expected");
      }
    } else {
      serverIpv4Address = deriveServerIpv4Address();
    }
    AdmissionTokenGenerator firstGenerator = null;
    for (AdmissionTokenGeneratorConfig tc : cfg.tokenGenerators()) {
      char prefix = tc.prefix().charAt(0);
      DefaultAdmissionTokenGenerator generator =
          new DefaultAdmissionTokenGenerator(serverIpv4Address, prefix, files.secret(tc.secret()));
      if (firstGenerator == null) {
        firstGenerator = generator;
      }
      tokenGenerators.put(prefix, generator);
    }
    tokenGenerator = firstGenerator;
  }

  byte[] deriveServerIpv4Address() {
    boolean error = false;
    try {
      Optional<Inet4Address> optInet4Address = PublicIpv4Finder.findFirstPublicIpv4();
      if (optInet4Address.isPresent()) {
        return optInet4Address.get().getAddress();
      }
    } catch (SocketException e) {
      error = true;
      LOG.error("Cannot determine public IPv4 server address " + e);
    }
    if (!error) {
      LOG.error("No public IPv4 address found");
    }
    return new byte[] {1, 1, 1, 1};
  }

  BypassCheck chainBypassCheck(BypassCheck first, BypassCheck second) {
    if (first == BypassCheck.NO_BYPASS) {
      return second;
    }
    return new BypassCheck() {
      @Override
      public boolean allowBypass(Channel channel, HttpRequest request) {
        return first.allowBypass(channel, request) || second.allowBypass(channel, request);
      }
    };
  }

  boolean needsBypass(ChannelHandlerContext ctx, HttpRequest request) {
    return !noBypassCheck.skipBypass(ctx, request)
        && bypassCheck.allowBypass(ctx.channel(), request);
  }

  void outputChallengeResources(ChannelHandlerContext ctx, HttpRequest request) {
    String uri = request.uri();
    int idx = uri.lastIndexOf('/');
    if (idx + 1 >= uri.length()) {
      FullHttpResponse response =
          new DefaultFullHttpResponse(
              HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER);
      ctx.writeAndFlush(response);
      return;
    }
    String fileName = uri.substring(idx + 1);
    ResourceFiles.FileInfo file = challengeFiles.getFile(fileName);
    if (file == null) {
      FullHttpResponse response =
          new DefaultFullHttpResponse(
              HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER);
      ctx.writeAndFlush(response);
      return;
    }
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, file.buf().retainedDuplicate());
    response
        .headers()
        .set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
    response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
    response.headers().set(HttpHeaderNames.EXPIRES, "0");
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, file.mimeType());
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, file.buf().readableBytes());
    ctx.writeAndFlush(response);
  }

  void outputChallengeHtml(
      ChannelHandlerContext ctx, HttpRequest request, AdmissionCheck admission) {
    String challenge = challengeVerification.generateChallenge();
    String msg = htmlTemplate;
    msg = msg.replaceAll("files/", CHALLENGE_RESOURCES_URL + "/");
    msg = msg.replace("{{ENDPOINT}}", CHALLENGE_ANSWER_URL);
    msg = msg.replace("{{STEP}}", CHALLENGE_STEP_URL);
    msg = msg.replace("{{CHALLENGE}}", challenge);
    msg = msg.replace("{{VERIFY_URL}}", CHALLENGE_ANSWER_URL);
    msg = msg.replace("{{PREFIX}}", challengeVerification.getTargetPrefix());
    msg = msg.replace("{{MAX_ITERATIONS}}", Integer.toString(powMaxIterations));
    // from the channel allocator, so the plaintext is already in direct memory for BoringSSL,
    // see the comment in ResourceFiles. buffer() rather than directBuffer() to keep honouring
    // -Dio.netty.noPreferDirect.
    ByteBuf buf = ctx.alloc().buffer(msg.length());
    buf.writeCharSequence(msg, CharsetUtil.UTF_8);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, buf);
    response
        .headers()
        .set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
    response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
    response.headers().set(HttpHeaderNames.EXPIRES, "0");
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
    addCookie(response, CHALLENGE_COOKIE_NAME, challenge, true, COOKIE_MAX_AGE_SECONDS);
    if (admission.expiredToken() != null) {
      addHistoryCookie(response, request, admission);
      // A challenge page reload must not count the same expired admission again.
      addCookie(response, TOKEN_COOKIE_NAME, "", false, 0);
    }
    ctx.writeAndFlush(response);
  }

  /// Advances the verified history of the request or starts it with the admission that just
  /// expired, and sets the history headers of the request to the issued values for the log.
  private void addHistoryCookie(
      FullHttpResponse response, HttpRequest request, AdmissionCheck admission) {
    History history = admission.history();
    History next =
        history == null
            ? new History(admission.expiredToken(), 1)
            : new History(history.originalToken(), history.revisits() + 1);
    setHistoryHeaders(request, next);
    String payload = next.originalToken() + "." + next.revisits();
    String value =
        payload + "." + tokenGenerators.get(next.originalToken().charAt(0)).signHistory(payload);
    addCookie(response, HISTORY_COOKIE_NAME, value, true, COOKIE_MAX_AGE_SECONDS);
  }

  /// Passes the history to the request log, like [#ADMISSION_TOKEN_HEADER].
  private static void setHistoryHeaders(HttpRequest request, History history) {
    request.headers().set(ADMISSION_ORIGINAL_TOKEN_HEADER, history.originalToken());
    request.headers().set(ADMISSION_REVISITS_HEADER, Long.toString(history.revisits()));
  }

  /// Appends a host-only cookie so the challenge and history can travel in the same response.
  private static void addCookie(
      FullHttpResponse response, String name, String value, boolean httpOnly, long maxAge) {
    Cookie cookie = new DefaultCookie(name, value);
    cookie.setHttpOnly(httpOnly);
    cookie.setSecure(true);
    cookie.setPath("/");
    cookie.setMaxAge(maxAge);
    response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
  }

  /// Sends the admission cookie for a correct answer or status 400.
  ///
  /// @return `true` if the answer is correct
  /// Checks the challenge answer in the request, without responding, see
  /// [#respondToChallengeAnswer]
  ///
  /// @return true, if the answer is correct
  boolean verifyChallengeAnswer(HttpRequest req) {
    QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
    Map<String, List<String>> params = decoder.parameters();
    String challenge = firstParameter(params, "challenge");
    String nonce = firstParameter(params, "nonce");
    long t =
        challenge == null || nonce == null
            ? 0
            : challengeVerification.verifyChallengeResponse(challenge, nonce);
    return t > 0;
  }

  /// Responds to a challenge answer: issues the admission cookie if it was correct, see
  /// [#verifyChallengeAnswer]. The issued token is also set as [#ADMISSION_TOKEN_HEADER] on the
  /// request, so the request log has it. An answer with an expired admission advances the history,
  /// see [#checkAdmissionCookie(HttpRequest, long)].
  void respondToChallengeAnswer(
      ChannelHandlerContext ctx, HttpRequest request, boolean correct, AdmissionCheck admission) {
    FullHttpResponse response;
    if (correct) {
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      String cookieValue = tokenGenerator.newAdmission();
      request.headers().set(ADMISSION_TOKEN_HEADER, cookieValue);
      if (admission.expiredToken() != null) {
        addHistoryCookie(response, request, admission);
      }
      addCookie(response, TOKEN_COOKIE_NAME, cookieValue, false, COOKIE_MAX_AGE_SECONDS);
    } else {
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
    }
    ctx.writeAndFlush(response);
  }

  /// @return the first value of the parameter, `null` if absent or empty
  private static String firstParameter(Map<String, List<String>> params, String name) {
    List<String> values = params.get(name);
    if (values == null || values.isEmpty() || values.getFirst().isEmpty()) {
      return null;
    }
    return values.getFirst();
  }

  /// Result of [#checkAdmissionCookie(HttpRequest)]
  ///
  /// @param expiredToken the authentic admission token that is too old, or `null`
  /// @param history the verified `admission0` history the request carries, or `null`
  record AdmissionCheck(boolean accepted, String expiredToken, History history) {
    static final AdmissionCheck ACCEPTED = new AdmissionCheck(true, null, null);
    static final AdmissionCheck REJECTED = new AdmissionCheck(false, null, null);
  }

  /// Content of a verified `admission0` cookie
  ///
  /// @param originalToken the admission whose expiry started the history
  /// @param revisits how many expired admissions the history records, starting at 1
  record History(String originalToken, long revisits) {}

  AdmissionCheck checkAdmissionCookie(HttpRequest request) {
    return checkAdmissionCookie(request, admissionClock.getAsLong());
  }

  /// Checks the admission cookie in the request against the current wall clock `nowMillis`, so
  /// the age limits are testable without real time, see [#checkAdmissionToken]. Verifies the
  /// `admission0` history cookie only when the request carries one, so other requests pay no HMAC.
  /// A verified history is set as [#ADMISSION_ORIGINAL_TOKEN_HEADER] and
  /// [#ADMISSION_REVISITS_HEADER] on the request, so the request log has it.
  AdmissionCheck checkAdmissionCookie(HttpRequest request, long nowMillis) {
    String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
    if (cookieHeader == null) {
      return AdmissionCheck.REJECTED;
    }
    String admissionToken = null;
    History history = null;
    for (Cookie c : ServerCookieDecoder.LAX.decode(cookieHeader)) {
      if (c.name().equals(TOKEN_COOKIE_NAME)) {
        admissionToken = c.value();
      } else if (c.name().equals(HISTORY_COOKIE_NAME)) {
        history = verifyHistory(c.value());
      }
    }
    AdmissionCheck check = checkAdmissionToken(request, admissionToken, nowMillis);
    if (history == null) {
      return check;
    }
    setHistoryHeaders(request, history);
    return new AdmissionCheck(check.accepted(), check.expiredToken(), history);
  }

  /// Checks the token of the admission cookie. A token is accepted only when its checksum is
  /// valid, it is not older than the configured maximum age and its creation time is not further
  /// in the future than the configured clock skew allowance.
  ///
  /// @param admissionToken the cookie value, `null` if there is no admission cookie
  private AdmissionCheck checkAdmissionToken(
      HttpRequest request, String admissionToken, long nowMillis) {
    if (admissionToken == null || admissionToken.isEmpty()) {
      return AdmissionCheck.REJECTED;
    }
    AdmissionTokenGenerator generator = tokenGenerators.get(admissionToken.charAt(0));
    if (generator == null) {
      return AdmissionCheck.REJECTED;
    }
    long creationMillis = generator.checkAdmission(admissionToken);
    if (creationMillis == 0 || admissionTooFarFuture(creationMillis, nowMillis)) {
      return AdmissionCheck.REJECTED;
    }
    if (admissionExpired(creationMillis, nowMillis)) {
      return new AdmissionCheck(false, admissionToken, null);
    }
    request.headers().set(ADMISSION_TOKEN_HEADER, admissionToken);
    return AdmissionCheck.ACCEPTED;
  }

  /// Parses an `admission0` cookie value, `original-token.count.tag`, and checks its signature
  /// with the secret of the token generator its prefix names.
  ///
  /// @return the history, `null` if malformed, forged or signed with a retired key
  private History verifyHistory(String value) {
    int tokenEnd = DefaultAdmissionTokenGenerator.TOKEN_CHARS;
    int signatureStart = value.lastIndexOf('.');
    if (signatureStart <= tokenEnd + 1 || value.charAt(tokenEnd) != '.') {
      return null;
    }
    DefaultAdmissionTokenGenerator signer = tokenGenerators.get(value.charAt(0));
    String payload = value.substring(0, signatureStart);
    if (signer == null || !signer.verifyHistory(payload, value.substring(signatureStart + 1))) {
      return null;
    }
    return new History(
        value.substring(0, tokenEnd), Long.parseLong(payload.substring(tokenEnd + 1)));
  }

  /// @return `true` if the token is older than the configured maximum admission age
  private boolean admissionExpired(long creationMillis, long nowMillis) {
    return nowMillis - creationMillis > admissionMaxAgeMillis;
  }

  /// @return `true` if the token creation time lies further in the future than the configured
  ///   clock skew allowance, which happens when issuing and verifying servers disagree on the
  ///   wall clock
  private boolean admissionTooFarFuture(long creationMillis, long nowMillis) {
    return creationMillis - nowMillis > admissionClockSkewMillis;
  }
}
