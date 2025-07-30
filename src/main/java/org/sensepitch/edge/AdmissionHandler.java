package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

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
public class AdmissionHandler extends ChannelInboundHandlerAdapter implements HasMetrics {

  private static final ProxyLogger LOG = ProxyLogger.get(AdmissionHandler.class);

  public static final String VERIFICATION_URL = "/.sensepitch.challenge.answer";
  public static final String htmlTemplate = ResourceLoader.loadTextFile("challenge.html");
  public static String cookieName = "sensepitch-pass";
  /** Request header containing the validated admission token */
  public static String ADMISSION_TOKEN_HEADER = "X-Sensepitch-Admission-Token";

  ChallengeGenerationAndVerification challengeVerification = new ChallengeGenerationAndVerification();
  private final NoBypassCheck noBypassCheck;
  private final BypassCheck bypassCheck;
  private final AdmissionTokenGenerator tokenGenerator;
  private final Map<Character, AdmissionTokenGenerator> tokenGenerators = new HashMap<>();

  LongAdder challengeSentCounter = new LongAdder();
  LongAdder challengeAnsweredCounter = new LongAdder();
  LongAdder challengeAnswerRejectedCounter = new LongAdder();
  LongAdder passedRequestCounter = new LongAdder();
  LongAdder bypassRequestCounter = new LongAdder();

  AdmissionHandler(AdmissionConfig cfg) {
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
    for (AdmissionTokenGeneratorConfig tc : cfg.tokenGenerator()) {
      char prefix = tc.prefix().charAt(0);
       DefaultAdmissionTokenGenerator generator = new DefaultAdmissionTokenGenerator(serverIpv4Address, prefix, tc.secret());
       if (firstGenerator == null) { firstGenerator = generator; }
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
    return new byte[]{1, 1, 1, 1};
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
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    LOG.traceChannelRead(ctx, msg);
    if (msg instanceof HttpRequest request) {
      if (checkAdmissionCookie(request)) {
        passedRequestCounter.increment();
        ctx.fireChannelRead(msg);
      } else if (!noBypassCheck.skipBypass(ctx, request) && bypassCheck.allowBypass(ctx.channel(), request)) {
        bypassRequestCounter.increment();
        ctx.fireChannelRead(msg);
      } else if (request.method() == HttpMethod.GET && request.uri().startsWith(VERIFICATION_URL)) {
        handleChallengeAnswer(ctx, request);
        ReferenceCountUtil.release(request);
        discardFollowingContent(ctx);
      } else {
        // TODO: behaviour of non GET requests?
        outputChallengeHtml(ctx.channel());
        ReferenceCountUtil.release(request);
        discardFollowingContent(ctx);
      }
    } else {
      ctx.fireChannelRead(msg);
    }
  }

  /**
   * Discard content messages that may follow the request until we receive
   * a LastHttpCount message. Revert back, because we may receive new requests
   * on the same channel (http keep alive).
   */
  void discardFollowingContent(ChannelHandlerContext ctx) {
    ChannelInboundHandler inboundHandler = new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
          ctx.pipeline().replace(this, "admission", AdmissionHandler.this);
        }
        ReferenceCountUtil.release(msg);
      }
    };
    ctx.pipeline().replace(this, "discard", inboundHandler);
  }

  public Metrics getMetrics() {
    return new Metrics();
  }

  public class Metrics {
    public long getChallengeSentCount() { return challengeSentCounter.longValue(); }
    public long getChallengeAnsweredCount() { return challengeAnsweredCounter.longValue(); }
    public long getChallengeAnswerRejectedCount() { return challengeAnswerRejectedCounter.longValue(); }
    public long getPassRequestCount() { return passedRequestCounter.longValue(); }
    public long getBypassRequestCount() { return bypassRequestCounter.longValue(); }
  }

  private void outputChallengeHtml(Channel channel) {
    String msg = htmlTemplate.replace("{{CHALLENGE}}", challengeVerification.generateChallenge());
    msg = msg.replace("{{VERIFY_URL}}", VERIFICATION_URL);
    msg = msg.replace("{{PREFIX}}", challengeVerification.getTargetPrefix());
    ByteBuf buf = Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8);
    FullHttpResponse response =
      new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, buf);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
    channel.writeAndFlush(response);
    challengeSentCounter.increment();
  }

  private void handleChallengeAnswer(ChannelHandlerContext ctx, HttpRequest req) {
    QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
    Map<String, List<String>> params = decoder.parameters();
    String challenge = params.get("challenge").get(0);
    String nonce = params.get("nonce").get(0);
    long t = challengeVerification.verifyChallengeParameters(challenge, nonce);
    FullHttpResponse response;
    if (t > 0) {
      challengeAnsweredCounter.increment();
      response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      String cookieValue = tokenGenerator.newAdmission();
      Cookie cookie = new DefaultCookie(cookieName, cookieValue);
      cookie.setHttpOnly(true);
      cookie.setSecure(true);
      cookie.setPath("/");
      cookie.setMaxAge(60 * 60 * 24 * 30);
      String encodedCookie = ServerCookieEncoder.STRICT.encode(cookie);
      response.headers().set(HttpHeaderNames.SET_COOKIE, encodedCookie);
    } else {
      challengeAnswerRejectedCounter.increment();
      response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
    }
    ctx.writeAndFlush(response);
  }

  private boolean checkAdmissionCookie(HttpRequest request) {
    String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
    if (cookieHeader != null) {
      Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieHeader);
      for (Cookie c : cookies) {
        if (c.name().equals(cookieName)) {
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
