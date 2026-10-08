package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
public class DetectCrawlerTest {

  @Test
  public void testCrawlerTsv() throws Exception {
    DetectCrawler detectCrawler = new DetectCrawler(DetectCrawlerConfig.builder().build());
    assertThat(checkBypass(detectCrawler, "Any")).isFalse();
    assertThat(checkBypass(detectCrawler, null)).isFalse();
    assertThat(checkBypass(detectCrawler, "Twitterbot/1.0")).isTrue();
    assertThat(checkBypass(detectCrawler, "+https://openai.com/gptbot")).isTrue();
  }

  @Test
  public void testCrawlerIpMatch() {
    DetectCrawler detectCrawler = new DetectCrawler(DetectCrawlerConfig.builder().build());
    String amazonbotAgent =
        "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Amazonbot/0.1;"
            + " +https://developer.amazon.com/support/amazonbot) Chrome/119.0.6045.214"
            + " Safari/537.36";
    // the user agent alone is not sufficient, Amazonbot is detected by its IP
    assertThat(checkBypass(detectCrawler, amazonbotAgent)).isFalse();
    HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().set(HttpHeaderNames.USER_AGENT, amazonbotAgent);
    request.headers().set(IpTraitsHandler.TRAITS_HEADER, "asn=14618, crawler=amazon:amazonbot");
    assertThat(detectCrawler.allowBypass(null, request)).isTrue();
    assertThat(request.headers().get(Deflector.TRAFFIC_FLAVOR_HEADER))
        .isEqualTo(Deflector.FLAVOR_CRAWLER);
    assertThat(request.headers().get(BypassCheck.HEADER)).isEqualTo("crawler-ip-match");
  }

  private static boolean checkBypass(DetectCrawler detectCrawler, String userAgent) {
    HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    if (userAgent != null) {
      request.headers().set(HttpHeaderNames.USER_AGENT, userAgent);
    }
    boolean f = detectCrawler.allowBypass(null, request);
    return f;
  }
}
