package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class DetectCrawlerTest {

  @Test
  public void testCrawlerTsv() throws Exception {
    DetectCrawler detectCrawler = new DetectCrawler(DetectCrawlerConfig.builder().build());
    assertThat(checkBypass(detectCrawler, "Any")).isFalse();
    assertThat(checkBypass(detectCrawler, null)).isFalse();
    assertThat(checkBypass(detectCrawler, "Twitterbot/1.0")).isTrue();
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
