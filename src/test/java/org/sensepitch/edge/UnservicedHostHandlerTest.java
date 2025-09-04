package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnservicedHostHandlerTest {

  private EmbeddedChannel channel;

  @BeforeEach
  void setUp() {
    // build real RedirectConfig instead of mocking
    UnservicedHostConfig cfg =
        UnservicedHostConfig.builder()
            .servicedDomains(Set.of("www.foo.com", "bar.com", "www.baz.com"))
            .defaultLocation("https://default.example")
            .build();
    channel = new EmbeddedChannel(new UnservicedHostHandler(cfg));
  }

  @Test
  void allowedHost_passesThrough() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, "bar.com");
    boolean forwarded = channel.writeInbound(req);
    assertThat(forwarded).isTrue();
    Object forwardedMsg = channel.readInbound();
    assertThat(forwardedMsg).isSameAs(req);
  }

  @Test
  void missingHost_resultsInBadRequestStatus() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    // no Host header
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.status()).isEqualTo(BAD_REQUEST);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION) == null).isTrue();
  }

  @Test
  void missingHostAfterSanitize_resultsInBadRequestStatus() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, SanitizeHostHandler.MISSING_HOST);
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.status()).isEqualTo(BAD_REQUEST);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION) == null).isTrue();
  }

  /** Set by {@link SanitizeHostHandler} */
  @Test
  void unknownHost_resultsInBadRequestStatus() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, SanitizeHostHandler.UNKNOWN_HOST);
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION) == null).isTrue();
  }

  @Test
  void mappedHost_redirectsToMappedTarget() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, "foo.com");
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.status()).isEqualTo(PERMANENT_REDIRECT);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("https://www.foo.com");
  }

  @Test
  void otherHost_redirectsToDefaultTarget() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, "other.com");
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.status()).isEqualTo(TEMPORARY_REDIRECT);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("https://default.example");
  }

  @Test
  void otherHostWithUri_redirectsToDefaultTarget() {
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/anything");
    req.headers().set(HttpHeaderNames.HOST, "other.com");
    HttpResponse resp = writeAndExpectResponse(req);
    assertThat(resp.status()).isEqualTo(TEMPORARY_REDIRECT);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
        .isEqualTo("https://default.example" + UnservicedHostHandler.NOT_FOUND_URI);
  }

  @Test
  void secondRequestWorks() {
    missingHost_resultsInBadRequestStatus();
    // continue with any other operation to assert that is working
    mappedHost_redirectsToMappedTarget();
  }

  private HttpResponse writeAndExpectResponse(HttpRequest req) {
    assertThat(channel.writeInbound(req)).isFalse();
    HttpResponse resp = channel.readOutbound();
    checkThatIngressContentConsumed();
    return resp;
  }

  private void checkThatIngressContentConsumed() {
    HttpContent content = new DefaultHttpContent(Unpooled.buffer());
    // write and check its consumed
    assertThat(channel.writeInbound(content)).isFalse();
    assertThat(content.refCnt()).isZero();
    // write and check its consumed
    content = new DefaultLastHttpContent(Unpooled.buffer());
    assertThat(channel.writeInbound(content)).isFalse();
    assertThat(content.refCnt()).isZero();
  }
}
