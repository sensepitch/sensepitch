package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    assertThat(request.headers().get(Deflector.TRAFFIC_TYPE_HEADER))
        .isEqualTo(Deflector.TRAFFIC_BOT);
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

  static final String TSV_HEADER =
      "organization\tcategory\tASN\tremark\tfragment-agent-match\tagent-match\n";

  /// An own crawler list replaces the built-in one.
  @Test
  public void ownTsvWithoutBuiltinDatabase(@TempDir Path dir) throws Exception {
    Path tsv = dir.resolve("crawlers.tsv");
    Files.writeString(tsv, TSV_HEADER + "Me\tcrawler\t\t\t\tMyBot/1.0\n");
    DetectCrawler detectCrawler =
        new DetectCrawler(
            DetectCrawlerConfig.builder()
                .disableBuiltinDatabase(true)
                .crawlerTsv(tsv.toString())
                .build());
    assertThat(checkBypass(detectCrawler, "MyBot/1.0")).isTrue();
    assertThat(checkBypass(detectCrawler, "Twitterbot/1.0")).isFalse();
  }

  @Test
  public void missingTsvFile(@TempDir Path dir) {
    String missing = dir.resolve("missing.tsv").toString();
    assertThatThrownBy(
            () -> new DetectCrawler(DetectCrawlerConfig.builder().crawlerTsv(missing).build()))
        .hasStackTraceContaining("missing.tsv");
  }

  /// The fragment is matched as a part of the agent and must contain a URL.
  @Test
  public void fragmentWithoutUrlIsRejected(@TempDir Path dir) throws Exception {
    Path tsv = dir.resolve("crawlers.tsv");
    Files.writeString(tsv, TSV_HEADER + "Me\tcrawler\t\t\tno-url\tMyBot/1.0\n");
    assertThatThrownBy(
            () ->
                new DetectCrawler(
                    DetectCrawlerConfig.builder()
                        .disableBuiltinDatabase(true)
                        .crawlerTsv(tsv.toString())
                        .build()))
        .hasMessageContaining("Error reading TSV row 0")
        .hasStackTraceContaining("fragment must contain http");
  }
}
