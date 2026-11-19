package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Request log failures and the log of a response that could not be delivered completely. A
/// failing log destination is a request level event: counted, logged at debug, see [ProxyLogger],
/// and the request outcome is counted before.
class RequestLoggingHandlerTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  record Logged(int status, long contentBytes, int upstreamStatus, RequestOutcome outcome) {}

  final ProxyMetrics metrics = new ProxyMetrics();
  final List<Logged> logged = new ArrayList<>();

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

  RequestLoggingHandler loggingHandler() {
    return new RequestLoggingHandler(
        metrics,
        info ->
            logged.add(
                new Logged(
                    info.response().status().code(),
                    info.contentBytes(),
                    info.upstreamStatus(),
                    info.outcome())));
  }

  @Test
  void failingRequestLogIsCounted() {
    ProxyMetrics metrics = new ProxyMetrics();
    IllegalStateException cause = new IllegalStateException("broken destination");
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new RequestLoggingHandler(
                metrics,
                info -> {
                  throw cause;
                }));
    channel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        LastHttpContent.EMPTY_LAST_CONTENT);
    channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    assertThat(metrics.requestLogErrorCounter.get()).isOne();
    assertThat(logs.errors()).isEmpty();
    assertThat(logs.entries())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(System.Logger.Level.DEBUG);
              assertThat(e.error()).isSameAs(cause);
            });
    channel.finishAndReleaseAll();
  }

  /// The client did not receive the complete response, so the record reports the aborted delivery
  /// instead of the upstream status. The upstream status is kept for diagnosis.
  @Test
  void failedFinalWriteLogsAbortedDelivery() {
    RequestLoggingHandler handler = loggingHandler();
    HttpObject response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
    EmbeddedChannel channel = failingChannel(handler, respond(true, response));

    request(channel);

    assertThat(logged).containsExactly(new Logged(499, 0, 200, RequestOutcome.CLIENT_ABORTED));
    assertThat(metrics.ingressRequestsCompleted.labelValues(SanitizeHostHandler.MISSING_HOST).get())
        .isZero();
    assertThat(metrics.ingressRequestsAborted.labelValues(SanitizeHostHandler.MISSING_HOST).get())
        .isOne();
    channel.finishAndReleaseAll();
  }

  /// Content written before the failed final write reached the client and is still logged. The
  /// content of the failed write is not.
  @Test
  void failedFinalWriteLogsOnlyDeliveredContent() {
    RequestLoggingHandler handler = loggingHandler();
    EmbeddedChannel channel =
        failingChannel(
            handler,
            respond(
                true,
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
                new DefaultHttpContent(Unpooled.copiedBuffer("delivered", StandardCharsets.UTF_8)),
                new DefaultLastHttpContent(Unpooled.copiedBuffer("lost", StandardCharsets.UTF_8))));

    request(channel);

    assertThat(logged).containsExactly(new Logged(499, 9, 200, RequestOutcome.CLIENT_ABORTED));
    channel.finishAndReleaseAll();
  }

  /// A response generated by the edge has no upstream status to retain.
  @Test
  void failedEdgeResponseLogsNoUpstreamStatus() {
    RequestLoggingHandler handler = loggingHandler();
    HttpObject response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.NOT_FOUND,
            Unpooled.copiedBuffer("missing", StandardCharsets.UTF_8));
    EmbeddedChannel channel = failingChannel(handler, respond(false, response));

    request(channel);

    assertThat(logged).containsExactly(new Logged(499, 0, 0, RequestOutcome.CLIENT_ABORTED));
    channel.finishAndReleaseAll();
  }

  /// A fallback page replaces an upstream status, which is the original status worth logging when
  /// the fallback itself cannot be delivered.
  @Test
  void failedFallbackWriteKeepsTheReplacedUpstreamStatus() {
    RequestLoggingHandler handler = loggingHandler();
    EmbeddedChannel channel =
        failingChannel(
            handler,
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof HttpRequest) {
                  handler.setUpstream(UPSTREAM);
                }
                if (msg instanceof LastHttpContent) {
                  handler.setOutcome(RequestOutcome.FALLBACK);
                  handler.setUpstreamStatus(503);
                  ctx.writeAndFlush(
                      new DefaultFullHttpResponse(
                          HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE));
                }
                ReferenceCountUtil.release(msg);
              }
            });

    request(channel);

    assertThat(logged).containsExactly(new Logged(499, 0, 503, RequestOutcome.FALLBACK));
    channel.finishAndReleaseAll();
  }

  /// A successful write keeps the response status and its complete content.
  @Test
  void completedWriteKeepsResponse() {
    RequestLoggingHandler handler = loggingHandler();
    HttpObject response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.CREATED,
            Unpooled.copiedBuffer("body", StandardCharsets.UTF_8));
    EmbeddedChannel channel = new EmbeddedChannel(handler, respond(true, response));

    request(channel);

    assertThat(logged).containsExactly(new Logged(201, 4, 0, RequestOutcome.COMPLETED));
    assertThat(metrics.ingressRequestsCompleted.labelValues(SanitizeHostHandler.MISSING_HOST).get())
        .isOne();
    assertThat(metrics.ingressRequestsAborted.labelValues(SanitizeHostHandler.MISSING_HOST).get())
        .isZero();
    channel.finishAndReleaseAll();
  }

  static void request(EmbeddedChannel channel) {
    channel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        LastHttpContent.EMPTY_LAST_CONTENT);
  }

  static ChannelInboundHandlerAdapter respond(boolean withUpstream, HttpObject... messages) {
    return new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest && withUpstream) {
          RequestLoggingHandler.find(ctx).setUpstream(UPSTREAM);
        }
        if (msg instanceof LastHttpContent) {
          for (HttpObject message : messages) {
            ctx.writeAndFlush(message);
          }
        }
        ReferenceCountUtil.release(msg);
      }
    };
  }

  static EmbeddedChannel failingChannel(
      RequestLoggingHandler handler, ChannelInboundHandlerAdapter responder) {
    return new EmbeddedChannel(
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof LastHttpContent) {
              ReferenceCountUtil.release(msg);
              promise.setFailure(new IOException("client connection closed"));
              return;
            }
            ctx.write(msg, promise);
          }
        },
        handler,
        responder);
  }
}
