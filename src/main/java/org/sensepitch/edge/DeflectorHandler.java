package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class DeflectorHandler extends SkippingChannelInboundHandlerAdapter implements HasMetrics {

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

  ChallengeGenerationAndVerification challengeVerification =
    new ChallengeGenerationAndVerification();
  private final NoBypassCheck noBypassCheck;
  private final BypassCheck bypassCheck;
  private final AdmissionTokenGenerator tokenGenerator;
  private final Map<Character, AdmissionTokenGenerator> tokenGenerators = new HashMap<>();

  LongAdder challengeSentCounter = new LongAdder();
  LongAdder challengeAnsweredCounter = new LongAdder();
  LongAdder challengeAnswerRejectedCounter = new LongAdder();
  LongAdder passedRequestCounter = new LongAdder();
  LongAdder bypassRequestCounter = new LongAdder();

  DeflectorHandler(DeflectorConfig cfg) {
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

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      if (checkAdmissionCookie(request)) {
        passedRequestCounter.increment();
        request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_USER);
        ctx.fireChannelRead(msg);
      } else if (!noBypassCheck.skipBypass(ctx, request)
          && bypassCheck.allowBypass(ctx.channel(), request)) {
        bypassRequestCounter.increment();
        ctx.fireChannelRead(msg);
      } else if (request.uri().startsWith(CHALLENGE_STEP_URL)) {
        request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_DEFLECT);
        FullHttpResponse response =
          new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        // Prevent caching
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        response.headers().set(HttpHeaderNames.EXPIRES, "0");
        ctx.writeAndFlush(response);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else if (request.method() == HttpMethod.GET && request.uri().startsWith(CHALLENGE_RESOURCES_URL)) {
        request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_DEFLECT);
        outputChallengeResources(ctx, request);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else if (request.method() == HttpMethod.GET && request.uri().startsWith(CHALLENGE_ANSWER_URL)) {
        request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_USER);
        handleChallengeAnswer(ctx, request);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      } else {
        // TODO: behaviour of non GET requests?
        request
            .headers()
            .set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_DEFLECT);
        outputChallengeHtml(ctx);
        ReferenceCountUtil.release(request);
        skipFollowingContent(ctx);
      }
    } else {
      // this skips content if completely handled here
      super.channelRead(ctx, msg);
    }
  }

  public Metrics getMetrics() {
    return new Metrics();
  }

  public class Metrics {
    public long getChallengeSentCount() {
      return challengeSentCounter.longValue();
    }

    public long getChallengeAnsweredCount() {
      return challengeAnsweredCounter.longValue();
    }

    public long getChallengeAnswerRejectedCount() {
      return challengeAnswerRejectedCounter.longValue();
    }

    public long getPassRequestCount() {
      return passedRequestCounter.longValue();
    }

    public long getBypassRequestCount() {
      return bypassRequestCounter.longValue();
    }
  }

  private void outputChallengeResources(ChannelHandlerContext ctx, HttpRequest request) {
    String uri = request.uri();
    int idx = uri.lastIndexOf('/');
    if (idx + 1 >= uri.length()) {
      FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER);
      ctx.writeAndFlush(response);
      return;
    }
    String fileName = uri.substring(idx + 1);
    ResourceFiles.FileInfo file = challengeFiles.getFile(fileName);
    if (file == null) {
      FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER);
      ctx.writeAndFlush(response);
      return;
    }
    FullHttpResponse response =
      new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, file.buf().retainedDuplicate());
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
    response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
    response.headers().set(HttpHeaderNames.EXPIRES, "0");
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, file.mimeType());
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, file.buf().readableBytes());
    ctx.writeAndFlush(response);
  }

  private void outputChallengeHtml(ChannelHandlerContext ctx) {
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
    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
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
    challengeSentCounter.increment();
  }

  private void handleChallengeAnswer(ChannelHandlerContext ctx, HttpRequest req) {
    QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
    Map<String, List<String>> params = decoder.parameters();
    String challenge = params.get("challenge").getFirst();
    String nonce = params.get("nonce").getFirst();
    long t = challengeVerification.verifyChallengeParameters(challenge, nonce);
    FullHttpResponse response;
    if (t > 0) {
      challengeAnsweredCounter.increment();
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      String cookieValue = tokenGenerator.newAdmission();
      Cookie cookie = new DefaultCookie(TOKEN_COOKIE_NAME, cookieValue);
      cookie.setHttpOnly(true);
      cookie.setSecure(true);
      cookie.setPath("/");
      cookie.setMaxAge(60 * 60 * 24 * 30);
      String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
      response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
    } else {
      challengeAnswerRejectedCounter.increment();
      response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
    }
    ctx.writeAndFlush(response);
  }

  private boolean checkAdmissionCookie(HttpRequest request) {
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
