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
import java.util.Set;

/**
 * @author Jens Wilke
 */
public class Deflector {

  private static final ProxyLogger LOG = ProxyLogger.get(DeflectorHandler.class);

  public static final String CHALLENGE_ANSWER_URL = "/.sensepitch.challenge.complete";
  public static final String CHALLENGE_STEP_URL = "/.sensepitch.challenge.step";
  public static final String CHALLENGE_RESOURCES_URL = "/.sensepitch.challenge.files";
  public static final String htmlTemplate = ResourceLoader.loadTextFile("challenge/challenge.html");
  public static final String TOKEN_COOKIE_NAME = "sensepitch-tk";
  public static final String CHALLENGE_COOKIE_NAME = "sensepitch-ch";
  private static final ResourceFiles challengeFiles = new ResourceFiles("challenge/files/");

  /** Request header containing the validated admission token */
  public static String ADMISSION_TOKEN_HEADER = "sensepitch-token";

  public static String TRAFFIC_FLAVOR_HEADER = "sensepitch-flavor";

  public static String FLAVOR_USER = "user";
  public static String FLAVOR_CRAWLER = "crawler";
  public static String FLAVOR_DEFLECT = "deflect";

  private final ChallengeGenerationAndVerification challengeVerification;
  private final NoBypassCheck noBypassCheck;
  private final BypassCheck bypassCheck;
  private final AdmissionTokenGenerator tokenGenerator;
  private final Map<Character, AdmissionTokenGenerator> tokenGenerators = new HashMap<>();

  Deflector(DeflectorConfig cfg) {
    challengeVerification =
      new ChallengeGenerationAndVerification(new TimeBasedChallenge(), cfg.hashTargetPrefix());
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
          new DefaultAdmissionTokenGenerator(serverIpv4Address, prefix, tc.secret());
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

  void outputChallengeHtml(ChannelHandlerContext ctx) {
    String challenge = challengeVerification.generateChallenge();
    String msg = htmlTemplate;
    msg = msg.replaceAll("files/", CHALLENGE_RESOURCES_URL + "/");
    msg = msg.replace("{{ENDPOINT}}", CHALLENGE_ANSWER_URL);
    msg = msg.replace("{{STEP}}", CHALLENGE_STEP_URL);
    msg = msg.replace("{{CHALLENGE}}", challenge);
    msg = msg.replace("{{VERIFY_URL}}", CHALLENGE_ANSWER_URL);
    msg = msg.replace("{{PREFIX}}", challengeVerification.getTargetPrefix());
    ByteBuf buf = Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, buf);
    response
        .headers()
        .set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
    response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
    response.headers().set(HttpHeaderNames.EXPIRES, "0");
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
    Cookie cookie = new DefaultCookie(CHALLENGE_COOKIE_NAME, challenge);
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setPath("/");
    cookie.setMaxAge(60 * 60 * 24 * 30);
    String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
    response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
    ctx.writeAndFlush(response);
  }

  void handleChallengeAnswer(ChannelHandlerContext ctx, HttpRequest req) {
    QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
    Map<String, List<String>> params = decoder.parameters();
    String challenge = params.get("challenge").getFirst();
    String nonce = params.get("nonce").getFirst();
    long t = challengeVerification.verifyChallengeResponse(challenge, nonce);
    FullHttpResponse response;
    if (t > 0) {
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      String cookieValue = tokenGenerator.newAdmission();
      Cookie cookie = new DefaultCookie(TOKEN_COOKIE_NAME, cookieValue);
      // since its not a session, its ok to expose
      // cookie.setHttpOnly(true);
      cookie.setSecure(true);
      cookie.setPath("/");
      cookie.setMaxAge(60 * 60 * 24 * 30);
      String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
      response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
    } else {
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
    }
    ctx.writeAndFlush(response);
  }

  boolean checkAdmissionCookie(HttpRequest request) {
    String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
    if (cookieHeader != null) {
      Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieHeader);
      for (Cookie c : cookies) {
        if (c.name().equals(TOKEN_COOKIE_NAME)) {
          String admissionToken = c.value();
          if (admissionToken == null || admissionToken.isEmpty()) {
            return false;
          }
          AdmissionTokenGenerator generator = tokenGenerators.get(admissionToken.charAt(0));
          if (generator == null) {
            return false;
          }
          long t = generator.checkAdmission(c.value());
          if (t > 0) {
            request.headers().set(ADMISSION_TOKEN_HEADER, admissionToken);
            return true;
          }
        }
      }
    }
    return false;
  }
}
