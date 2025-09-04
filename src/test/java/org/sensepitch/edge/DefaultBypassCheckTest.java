package org.sensepitch.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class DefaultBypassCheckTest {

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
}
