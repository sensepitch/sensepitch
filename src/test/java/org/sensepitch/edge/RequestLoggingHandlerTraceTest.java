package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// The handler assigns each request a W3C trace context, sets the `traceparent` header for
/// upstream and logs the same ids
///
/// @author Jens Wilke
class RequestLoggingHandlerTraceTest {

  static final String CLIENT_TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00";

  /// Values captured during the log call, the info is only valid then
  record Logged(
      String traceId,
      String spanId,
      String traceFlags,
      String clientTraceparent,
      long requestBodyBytes,
      int connectionRequestCount,
      String requestId,
      Object clientAddress,
      int serverPort,
      String tlsProtocol) {}

  Logged logged;
  HttpRequest forwarded;

  EmbeddedChannel channel(boolean trustTraceparent) {
    RequestLogger logger =
        info ->
            logged =
                new Logged(
                    info.traceId(),
                    info.spanId(),
                    info.traceFlags(),
                    info.clientTraceparent(),
                    info.requestBodyBytes(),
                    info.connectionRequestCount(),
                    info.requestId(),
                    info.clientAddress(),
                    info.serverPort(),
                    info.tlsProtocol());
    // stands in for the upstream: records the request as forwarded and responds
    ChannelInboundHandlerAdapter upstream =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest request) {
              forwarded = request;
            }
            if (msg instanceof LastHttpContent) {
              ctx.writeAndFlush(
                  new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            }
            ReferenceCountUtil.release(msg);
          }
        };
    return new EmbeddedChannel(
        new RequestLoggingHandler(new ProxyMetrics(), logger, trustTraceparent), upstream);
  }

  void request(EmbeddedChannel channel, HttpRequest request, String body) {
    HttpContent content =
        new DefaultHttpContent(
            channel.alloc().buffer().writeBytes(body.getBytes(StandardCharsets.US_ASCII)));
    channel.writeInbound(request, content, new DefaultLastHttpContent());
    ReferenceCountUtil.release(channel.readOutbound());
  }

  static HttpRequest get(String traceparent) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    if (traceparent != null) {
      request.headers().set(TraceContext.TRACEPARENT, traceparent);
      request.headers().set(TraceContext.TRACESTATE, "vendor=value");
    }
    return request;
  }

  @Test
  void newTraceWithoutClientHeader() {
    EmbeddedChannel channel = channel(false);
    request(channel, get(null), "hello");
    TraceContext sent = TraceContext.parse(forwarded);
    assertThat(sent).isNotNull();
    assertThat(sent.flags()).isEqualTo(TraceContext.SAMPLED);
    assertThat(logged.traceId()).isEqualTo(sent.traceId());
    assertThat(logged.spanId()).isEqualTo(sent.spanId());
    assertThat(logged.traceFlags()).isEqualTo("01");
    assertThat(logged.clientTraceparent()).isNull();
    assertThat(logged.requestBodyBytes()).isEqualTo(5);
    // an embedded channel has neither an IP address nor TLS
    assertThat(logged.clientAddress()).isNull();
    assertThat(logged.serverPort()).isZero();
    assertThat(logged.tlsProtocol()).isNull();
    channel.finishAndReleaseAll();
  }

  /// By default the client cannot choose the trace id, its header is replaced and logged
  @Test
  void untrustedClientHeaderIsReplaced() {
    EmbeddedChannel channel = channel(false);
    request(channel, get(CLIENT_TRACEPARENT), "");
    TraceContext sent = TraceContext.parse(forwarded);
    assertThat(sent.traceId()).isNotEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(forwarded.headers().contains(TraceContext.TRACESTATE)).isFalse();
    assertThat(logged.spanId()).isEqualTo(sent.spanId());
    assertThat(logged.clientTraceparent()).isEqualTo(CLIENT_TRACEPARENT);
    channel.finishAndReleaseAll();
  }

  @Test
  void trustedClientHeaderIsContinued() {
    EmbeddedChannel channel = channel(true);
    request(channel, get(CLIENT_TRACEPARENT), "");
    TraceContext sent = TraceContext.parse(forwarded);
    assertThat(sent.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(sent.flags()).as("sampling decision of the client is kept").isEqualTo("00");
    assertThat(sent.spanId()).isNotEqualTo("b7ad6b7169203331");
    assertThat(forwarded.headers().get(TraceContext.TRACESTATE)).isEqualTo("vendor=value");
    assertThat(logged.spanId()).isEqualTo(sent.spanId());
    assertThat(logged.clientTraceparent()).isEqualTo(CLIENT_TRACEPARENT);
    channel.finishAndReleaseAll();
  }

  /// Even when trusted, an invalid header starts a new trace
  @Test
  void trustedInvalidHeaderStartsNewTrace() {
    EmbeddedChannel channel = channel(true);
    request(channel, get("00-garbage"), "");
    TraceContext sent = TraceContext.parse(forwarded);
    assertThat(sent).isNotNull();
    assertThat(forwarded.headers().contains(TraceContext.TRACESTATE)).isFalse();
    assertThat(logged.clientTraceparent()).isEqualTo("00-garbage");
    channel.finishAndReleaseAll();
  }

  /// Each request of a keep-alive connection is a span of its own
  @Test
  void eachRequestGetsItsOwnSpan() {
    EmbeddedChannel channel = channel(false);
    request(channel, get(null), "first");
    Logged first = logged;
    request(channel, get(null), "");
    assertThat(logged.spanId()).isNotEqualTo(first.spanId());
    assertThat(first.requestBodyBytes()).isEqualTo(5);
    assertThat(logged.requestBodyBytes()).as("counter reset per request").isZero();
    assertThat(first.connectionRequestCount()).isZero();
    assertThat(logged.connectionRequestCount()).isOne();
    assertThat(logged.requestId()).endsWith("/1");
    channel.finishAndReleaseAll();
  }
}
