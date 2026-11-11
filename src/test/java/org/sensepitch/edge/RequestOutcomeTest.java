package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// How the request logging handler determines [RequestOutcome], and the values other handlers
/// report to it
///
/// @author Jens Wilke
class RequestOutcomeTest {

  /// Values captured during the log call, the info is only valid then
  record Logged(
      RequestOutcome outcome, int upstreamStatus, String upstreamName, String protectionOutcome) {}

  final List<Logged> logged = new ArrayList<>();
  EmbeddedChannel channel;

  static final Upstream UPSTREAM =
      new Upstream() {
        @Override
        public String name() {
          return "upstreams.backend";
        }

        @Override
        public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
          throw new UnsupportedOperationException();
        }
      };

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.finishAndReleaseAll();
    }
  }

  RequestLoggingHandler loggingHandler() {
    return new RequestLoggingHandler(
        new ProxyMetrics(),
        info ->
            logged.add(
                new Logged(
                    info.outcome(),
                    info.upstreamStatus(),
                    info.upstreamName(),
                    info.protectionOutcome())));
  }

  /// Stands in for the rest of the pipeline: reports to the logging handler as a real handler
  /// would and responds with the given status
  static ChannelInboundHandlerAdapter respond(HttpResponseStatus status, boolean withUpstream) {
    return new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest && withUpstream) {
          RequestLoggingHandler.find(ctx).setUpstream(UPSTREAM);
        }
        if (msg instanceof LastHttpContent) {
          ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status));
        }
        ReferenceCountUtil.release(msg);
      }
    };
  }

  void request() {
    channel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        new DefaultLastHttpContent());
  }

  @Test
  void upstreamResponseIsCompleted() {
    channel = new EmbeddedChannel(loggingHandler(), respond(HttpResponseStatus.OK, true));
    request();
    assertThat(logged)
        .containsExactly(new Logged(RequestOutcome.COMPLETED, 0, "upstreams.backend", null));
  }

  /// Without an upstream, the edge answered itself
  @Test
  void noUpstreamIsEdgeResponse() {
    channel = new EmbeddedChannel(loggingHandler(), respond(HttpResponseStatus.NOT_FOUND, false));
    request();
    assertThat(logged).containsExactly(new Logged(RequestOutcome.EDGE_RESPONSE, 0, null, null));
  }

  /// The fallback page replaces the 503 of the upstream, the original status is logged
  @Test
  void fallbackReportsTheUpstreamStatus() {
    channel =
        new EmbeddedChannel(
            loggingHandler(),
            new Fallback(
                    FallbackConfig.builder()
                        .unavailableResponse(ResponseConfig.builder().text("unavailable").build())
                        .errorResponse(ResponseConfig.builder().text("error").build())
                        .build())
                .newHandler(),
            respond(HttpResponseStatus.SERVICE_UNAVAILABLE, true));
    request();
    assertThat(logged)
        .containsExactly(new Logged(RequestOutcome.FALLBACK, 503, "upstreams.backend", null));
  }

  /// The connection closes before the response was sent
  @Test
  void closedConnectionIsClientAborted() {
    channel = new EmbeddedChannel(loggingHandler(), new ChannelInboundHandlerAdapter());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    channel.close();
    assertThat(logged).extracting(Logged::outcome).containsExactly(RequestOutcome.CLIENT_ABORTED);
  }

  /// The first report wins, e.g. a timeout before the connection closes
  @Test
  void firstReportWins() {
    channel = new EmbeddedChannel(loggingHandler(), new ChannelInboundHandlerAdapter());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    channel.pipeline().get(RequestLoggingHandler.class).setOutcome(RequestOutcome.UPSTREAM_TIMEOUT);
    channel.close();
    assertThat(logged).extracting(Logged::outcome).containsExactly(RequestOutcome.UPSTREAM_TIMEOUT);
  }

  /// A report arriving after the request was logged does not leak into the next request
  @Test
  void lateReportIsIgnored() {
    channel = new EmbeddedChannel(loggingHandler(), respond(HttpResponseStatus.OK, false));
    request();
    RequestLoggingHandler handler = channel.pipeline().get(RequestLoggingHandler.class);
    handler.setOutcome(RequestOutcome.EDGE_ERROR);
    handler.setProtectionOutcome(ProxyMetrics.DeflectorOutcome.PASSED);
    handler.setUpstream(UPSTREAM);
    handler.setUpstreamStatus(503);
    handler.setRoute("/late");
    request();
    assertThat(logged)
        .containsExactly(
            new Logged(RequestOutcome.EDGE_RESPONSE, 0, null, null),
            new Logged(RequestOutcome.EDGE_RESPONSE, 0, null, null));
  }

  @Test
  void outcomeValues() {
    assertThat(RequestOutcome.UPSTREAM_CONNECT_FAILED.value()).isEqualTo("upstream_connect_failed");
    assertThat(ProxyMetrics.DeflectorOutcome.CHALLENGE_SENT.value()).isEqualTo("challenge_sent");
  }
}
