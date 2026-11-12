package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

/// A client cannot send the headers of the edge
///
/// @author Jens Wilke
class StripEdgeHeadersHandlerTest {

  @Test
  void edgeHeadersAreRemovedOthersStay() {
    EmbeddedChannel channel = new EmbeddedChannel(new StripEdgeHeadersHandler());
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().add(Deflector.TRAFFIC_TYPE_HEADER, "human");
    request.headers().add("sp-admission-token", "forged");
    request.headers().add("SP-Protection-Bypass", "config-uri-match");
    request.headers().add("Spam", "1");
    request.headers().add("X-Custom", "2");
    request.headers().add("Sp", "3");
    channel.writeInbound(request);
    HttpRequest received = channel.readInbound();
    assertThat(received.headers().names()).containsExactlyInAnyOrder("Spam", "X-Custom", "Sp");
  }

  @Test
  void isEdgeHeader() {
    assertThat(SensepitchHeaders.isEdgeHeader("Sp-Ja4")).isTrue();
    assertThat(SensepitchHeaders.isEdgeHeader("sP-x")).isTrue();
    assertThat(SensepitchHeaders.isEdgeHeader("Sp")).isFalse();
    assertThat(SensepitchHeaders.isEdgeHeader("Spam")).isFalse();
    assertThat(SensepitchHeaders.isEdgeHeader("X-Sp-Ja4")).isFalse();
  }
}
