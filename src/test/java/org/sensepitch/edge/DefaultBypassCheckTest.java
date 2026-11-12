package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class DefaultBypassCheckTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  public void testBypassCheck() {
    BypassConfig cfg = BypassConfig.builder().uris(Arrays.asList("/abc*", "/ok*")).build();
    BypassCheck check = new DefaultBypassCheck(cfg);
    Channel channel = new EmbeddedChannel();
    Arrays.asList("/000", "/b", "/abd123", "zzz")
        .forEach(
            s -> {
              HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, s);
              assertFalse(check.allowBypass(channel, request), "No bypass: " + s);
            });
    Arrays.asList("/abc", "/abc1123", "/ok")
        .forEach(
            s -> {
              HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, s);
              assertTrue(check.allowBypass(channel, request), "Bypass: " + s);
            });
  }

  static HttpRequest get(String uri) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
  }

  @Test
  public void exactUri() {
    BypassCheck check = new DefaultBypassCheck(BypassConfig.builder().uris(List.of("/x")).build());
    Channel channel = new EmbeddedChannel();
    HttpRequest request = get("/x");
    assertTrue(check.allowBypass(channel, request));
    assertThat(request.headers().get(BypassCheck.HEADER)).isEqualTo("config-uri-match");
    assertFalse(check.allowBypass(channel, get("/x/")));
    assertFalse(check.allowBypass(channel, get("/")));
  }

  /// Every configured prefix counts, also when a longer prefix sorts in between.
  @Test
  public void overlappingPrefixes() {
    BypassCheck check =
        new DefaultBypassCheck(BypassConfig.builder().uris(List.of("/a*", "/ab*")).build());
    Channel channel = new EmbeddedChannel();
    for (String uri : List.of("/a", "/ab", "/abc", "/ac", "/azzz")) {
      HttpRequest request = get(uri);
      assertTrue(check.allowBypass(channel, request), "Bypass: " + uri);
      assertThat(request.headers().get(BypassCheck.HEADER)).isEqualTo("config-uri-match");
    }
    HttpRequest noBypass = get("/b");
    assertFalse(check.allowBypass(channel, noBypass));
    assertThat(noBypass.headers().contains(BypassCheck.HEADER)).isFalse();
    assertFalse(check.allowBypass(channel, get("/")));
  }

  @Test
  public void suffixIsNotSupported() {
    BypassConfig cfg = BypassConfig.builder().uris(List.of("*.png")).build();
    assertThrows(UnsupportedOperationException.class, () -> new DefaultBypassCheck(cfg));
  }

  @Test
  public void remoteAddress() {
    BypassCheck check =
        new DefaultBypassCheck(BypassConfig.builder().remotes(List.of("192.0.2.7")).build());
    HttpRequest request = get("/");
    assertTrue(check.allowBypass(TestChannels.channelFrom("192.0.2.7"), request));
    assertThat(request.headers().get(BypassCheck.HEADER)).isEqualTo("config-ip-match");
    assertFalse(check.allowBypass(TestChannels.channelFrom("192.0.2.8"), get("/")));
  }

  /// A DNS problem at startup is logged, the proxy still starts.
  @Test
  public void unresolvableRemoteIsLogged() {
    BypassCheck check =
        new DefaultBypassCheck(
            BypassConfig.builder().remotes(List.of("does-not-exist.invalid")).build());
    assertFalse(check.allowBypass(TestChannels.channelFrom("192.0.2.7"), get("/")));
    assertThat(logs.messages(DefaultBypassCheck.class))
        .anySatisfy(m -> assertThat(m).contains("does-not-exist.invalid"));
  }
}
