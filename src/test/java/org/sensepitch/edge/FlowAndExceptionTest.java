package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// @author Jens Wilke
public class FlowAndExceptionTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics proxyMetrics = new ProxyMetrics();

  /// When set, [MockUpstream] fails to connect with this cause
  Throwable connectFailure;

  /// When set, every write to the upstream channel fails
  boolean failUpstreamWrites;

  /// When set, [MockUpstream] returns a pending connect, completed by the test via
  /// [#pendingConnect]
  boolean deferConnect;

  Promise<Channel> pendingConnect;

  RequestLogCapture requestLogCapture = new RequestLogCapture();
  RequestLogger requestLog = requestLogCapture.plainText();

  EmbeddedChannel upstreamChannel;

  /// Simulates a slow client, see [FlushBlocker], set for each ingress channel
  FlushBlocker ingressFlushBlocker;

  /// Simulates a slow upstream, see [FlushBlocker]
  FlushBlocker upstreamFlushBlocker = new FlushBlocker();

  EmbeddedChannel ingressChannel = ingressChannel(null);

  /// @param remote remote address of the client, or `null` for the default of [EmbeddedChannel]
  EmbeddedChannel ingressChannel(SocketAddress remote) {
    // a handler instance can only be in one pipeline, so every ingress channel gets its own
    ingressFlushBlocker = new FlushBlocker();
    ChannelHandler[] handlers = {
      ingressFlushBlocker,
      new TimeoutsTest.FakeSslHandler(),
      new RequestLoggingHandler(proxyMetrics, requestLog),
      new IngressTimeoutHandler(ConnectionConfig.DEFAULT, proxyMetrics),
      new DownstreamHandler(new MockUpstream(), proxyMetrics),
      new ExceptionHandler(proxyMetrics)
    };
    if (remote == null) {
      return new EmbeddedChannel(handlers);
    }
    return new EmbeddedChannel(handlers) {
      @Override
      protected SocketAddress remoteAddress0() {
        return remote;
      }
    };
  }

  // ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));
  @Test
  public void messageIsBufferedInUpstreamAndWrittenAfterFlush() {
    assertThat(upstreamChannel).isNull();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    assertThat(upstreamChannel).isNotNull();
    var obj = upstreamChannel.readOutbound();
    assertThat(obj).isNull();
    upstreamChannel.flush();
    obj = upstreamChannel.readOutbound();
    assertThat(obj).isNotNull();
  }

  @Test
  public void upstreamChannelIsFlushed() {
    assertThat(upstreamChannel).isNull();
    assertThat(ingressChannel.isActive()).isTrue();
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    upstreamChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[1000])));
    assertThat(upstreamChannel.isWritable())
        .isTrue()
        .describedAs("Channel writable, buffer not full");
    assertThat(ingressChannel.config().getOption(ChannelOption.AUTO_READ))
        .isTrue()
        .describedAs("Ingress is reading");
    assertThat((Object) upstreamChannel.readOutbound()).isNull();
    ;
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[1000])));
    assertThat((Object) upstreamChannel.readOutbound()).isNotNull();
    assertThat(upstreamChannel.isWritable())
        .isTrue()
        .describedAs("Channel writable, buffer not full");
    assertThat(ingressChannel.config().getOption(ChannelOption.AUTO_READ))
        .isTrue()
        .describedAs("Ingress is reading");
  }

  @Test
  public void upstreamConnectionReset() {
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/post"));
    ingressChannel.writeInbound(new DefaultLastHttpContent());
    upstreamChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
  }

  /// A request with body: reading from the client pauses until the upstream is connected, so a
  /// large upload is not buffered in memory. Content that was already received is forwarded in
  /// order after the connect.
  @Test
  public void requestBodyWaitsForUpstreamConnect() {
    deferConnect = true;
    HttpRequest post = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/form");
    HttpUtil.setContentLength(post, 3);
    ingressChannel.writeInbound(post);
    assertThat(ingressChannel.config().isAutoRead()).isFalse();
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1, 2})));
    ingressChannel.writeInbound(new DefaultLastHttpContent(Unpooled.copiedBuffer(new byte[] {3})));
    pendingConnect.setSuccess(upstreamChannel(ingressChannel));
    rattle();
    assertThat(ingressChannel.config().isAutoRead()).isTrue();
    assertThat((Object) upstreamChannel.readOutbound()).isSameAs(post);
    HttpContent first = upstreamChannel.readOutbound();
    assertThat(first.content().readableBytes()).isEqualTo(2);
    first.release();
    LastHttpContent last = upstreamChannel.readOutbound();
    assertThat(last.content().readableBytes()).isEqualTo(1);
    last.release();
    assertThat(post.headers().get("X-Forwarded-Proto")).isEqualTo("https");
  }

  /// Without a body reading is not paused.
  @Test
  public void requestWithoutBodyKeepsReading() {
    deferConnect = true;
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
    assertThat(ingressChannel.config().isAutoRead()).isTrue();
    pendingConnect.setSuccess(upstreamChannel(ingressChannel));
    rattle();
    ingressChannel.finishAndReleaseAll();
  }

  /// Slow client: while the response cannot be written out, the proxy stops reading from the
  /// upstream, so a large response is not buffered in memory. Reading resumes once the client
  /// catches up.
  @Test
  public void slowClientThrottlesUpstream() {
    sendGetRequest();
    rattle();
    ingressChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    ingressFlushBlocker.blocked = true;
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    HttpUtil.setTransferEncodingChunked(response, true);
    upstreamChannel.writeInbound(
        response, new DefaultHttpContent(Unpooled.copiedBuffer(new byte[5000])));
    rattle();
    assertThat(ingressChannel.isWritable()).isFalse();
    assertThat(upstreamChannel.config().isAutoRead()).isFalse();
    ingressFlushBlocker.release(ingressChannel);
    rattle();
    assertThat(ingressChannel.isWritable()).isTrue();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
    assertThat((Object) readSkippingEmptyBuffers(ingressChannel)).isSameAs(response);
    HttpContent content = readSkippingEmptyBuffers(ingressChannel);
    assertThat(content.content().readableBytes()).isEqualTo(5000);
    content.release();
    ingressChannel.finishAndReleaseAll();
  }

  /// Slow upstream: while a request body cannot be written to the upstream, the proxy stops
  /// reading from the client. Reading resumes once the upstream catches up.
  @Test
  public void slowUpstreamThrottlesClient() {
    HttpRequest post = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    HttpUtil.setTransferEncodingChunked(post, true);
    ingressChannel.writeInbound(post);
    rattle();
    upstreamChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    upstreamFlushBlocker.blocked = true;
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[5000])));
    rattle();
    assertThat(upstreamChannel.isWritable()).isFalse();
    assertThat(ingressChannel.config().isAutoRead()).isFalse();
    upstreamFlushBlocker.release(upstreamChannel);
    rattle();
    assertThat(upstreamChannel.isWritable()).isTrue();
    assertThat(ingressChannel.config().isAutoRead()).isTrue();
    upstreamChannel.finishAndReleaseAll();
    ingressChannel.finishAndReleaseAll();
  }

  /// Slow upstream that is still unwritable after a flush, the buffer filled again meanwhile: the
  /// proxy flushes again until it is writable, then the client reads resume.
  @Test
  public void upstreamStillUnwritableAfterFlushIsFlushedAgain() {
    startUploadToBlockedUpstream();
    upstreamFlushBlocker.holdUnwritable(upstreamChannel, 2);
    upstreamFlushBlocker.release(upstreamChannel);
    rattle();
    assertThat(upstreamFlushBlocker.flushes).isGreaterThanOrEqualTo(2);
    assertThat(upstreamChannel.isWritable()).isTrue();
    assertThat(ingressChannel.config().isAutoRead()).isTrue();
    upstreamChannel.finishAndReleaseAll();
    ingressChannel.finishAndReleaseAll();
  }

  /// A failed flush toward the upstream, here because it closed, is not repeated
  @Test
  public void upstreamClosedDuringFlushIsNotFlushedAgain() {
    startUploadToBlockedUpstream();
    upstreamChannel.close();
    rattle();
    assertThat(upstreamFlushBlocker.flushes).isZero();
    ingressChannel.finishAndReleaseAll();
  }

  /// Request headers and a body chunk that does not fit, the upstream is unwritable and its flush
  /// is held by [#upstreamFlushBlocker]
  private void startUploadToBlockedUpstream() {
    HttpRequest post = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
    HttpUtil.setTransferEncodingChunked(post, true);
    ingressChannel.writeInbound(post);
    rattle();
    upstreamChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    upstreamFlushBlocker.blocked = true;
    ingressChannel.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[5000])));
    rattle();
    assertThat(upstreamChannel.isWritable()).isFalse();
    assertThat(ingressChannel.config().isAutoRead()).isFalse();
  }

  /// Slow client that is still unwritable after a flush: the proxy flushes again until it is
  /// writable, then the upstream reads resume.
  @Test
  public void clientStillUnwritableAfterFlushIsFlushedAgain() {
    startResponseToBlockedClient();
    ingressFlushBlocker.holdUnwritable(ingressChannel, 2);
    ingressFlushBlocker.release(ingressChannel);
    rattle();
    assertThat(ingressFlushBlocker.flushes).isGreaterThanOrEqualTo(2);
    assertThat(ingressChannel.isWritable()).isTrue();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
    ingressChannel.finishAndReleaseAll();
  }

  /// A failed flush toward the client, here because it closed, is not repeated
  @Test
  public void clientClosedDuringFlushIsNotFlushedAgain() {
    startResponseToBlockedClient();
    ingressChannel.close();
    rattle();
    assertThat(ingressFlushBlocker.flushes).isZero();
    ingressChannel.finishAndReleaseAll();
  }

  /// A response chunk that does not fit, the client is unwritable and its flush is held by
  /// [#ingressFlushBlocker]
  private void startResponseToBlockedClient() {
    sendGetRequest();
    rattle();
    ingressChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
    ingressFlushBlocker.blocked = true;
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    HttpUtil.setTransferEncodingChunked(response, true);
    upstreamChannel.writeInbound(
        response, new DefaultHttpContent(Unpooled.copiedBuffer(new byte[5000])));
    rattle();
    assertThat(ingressChannel.isWritable()).isFalse();
    assertThat(upstreamChannel.config().isAutoRead()).isFalse();
  }

  /// The response travels from upstream via [ForwardHandler] to the client, hop-by-hop headers are
  /// not forwarded. There is no connection pool, so the upstream connection is closed after the
  /// response, also if the upstream offers keep alive. The client connection stays open.
  @Test
  public void responseIsForwardedAndUpstreamClosed() {
    sendGetRequest();
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=5");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 3);
    HttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1, 2, 3}));
    upstreamChannel.writeInbound(response, content, new DefaultLastHttpContent());
    rattle();
    assertThat((Object) ingressChannel.readOutbound()).isSameAs(response);
    // the upstream's value is dropped, IngressTimeoutHandler sets the one of the proxy
    assertThat(response.headers().get(HttpHeaderNames.KEEP_ALIVE)).isNotEqualTo("timeout=5");
    HttpContent received = ingressChannel.readOutbound();
    assertThat(received.content().readableBytes()).isEqualTo(3);
    received.release();
    assertThat((Object) ingressChannel.readOutbound()).isInstanceOf(LastHttpContent.class);
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(ingressChannel.isOpen()).isTrue();
  }

  /// Upstream sends `Connection: close`, the upstream connection is closed like after every
  /// response.
  @Test
  public void upstreamConnectionCloseIsClosed() {
    sendGetRequest();
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    upstreamChannel.writeInbound(response, new DefaultLastHttpContent());
    rattle();
    assertThat(upstreamChannel.isOpen()).isFalse();
    ingressChannel.finishAndReleaseAll();
  }

  /// Data arriving after the response is complete has nowhere to go and must be released. The
  /// upstream connection is closed after the response, so this only happens when the extra bytes
  /// are decoded within the same read as the end of the response.
  @Test
  public void unexpectedDataFromUpstreamIsDropped() {
    sendGetRequest();
    HttpContent stray = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
        new DefaultLastHttpContent(),
        stray);
    rattle();
    ingressChannel.finishAndReleaseAll();
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(stray.refCnt()).isZero();
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(ForwardHandler.class.getName()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(Level.DEBUG);
              assertThat(e.info().channel()).isSameAs(upstreamChannel);
              assertThat(e.message()).contains("downstream is null", "DefaultHttpContent");
            });
    assertThat(upstreamErrors("unexpected_data")).isOne();
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.COMPLETED);
    assertThat(logs.errors()).isEmpty();
  }

  /// Finishing the response releases only a read pause imposed by this exchange.
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void earlyResponseRestoresClientReadState(boolean initiallyReading) {
    EmbeddedChannel downstream = new EmbeddedChannel();
    EmbeddedChannel upstream = (EmbeddedChannel) upstreamChannel(downstream);
    try {
      downstream.config().setAutoRead(initiallyReading);
      upstream.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
      upstreamFlushBlocker.blocked = true;
      upstream.writeAndFlush(Unpooled.wrappedBuffer(new byte[5000]));
      upstream.runPendingTasks();
      assertThat(upstream.isWritable()).isFalse();
      assertThat(downstream.config().isAutoRead()).isFalse();

      upstream.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);

      assertThat((Object) downstream.readOutbound()).isSameAs(LastHttpContent.EMPTY_LAST_CONTENT);
      assertThat(upstream.isWritable()).isFalse();
      assertThat(downstream.config().isAutoRead()).isEqualTo(initiallyReading);
    } finally {
      upstreamFlushBlocker.release(upstream);
      upstream.finishAndReleaseAll();
      downstream.finishAndReleaseAll();
    }
  }

  /// An upstream that becomes writable again releases only a read pause imposed by this exchange,
  /// the other way out of the pause besides [#earlyResponseRestoresClientReadState(boolean)].
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void writableAgainRestoresClientReadState(boolean initiallyReading) {
    EmbeddedChannel downstream = new EmbeddedChannel();
    EmbeddedChannel upstream = (EmbeddedChannel) upstreamChannel(downstream);
    try {
      downstream.config().setAutoRead(initiallyReading);
      upstream.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1000, 2000));
      upstreamFlushBlocker.blocked = true;
      upstream.writeAndFlush(Unpooled.wrappedBuffer(new byte[5000]));
      upstream.runPendingTasks();
      assertThat(upstream.isWritable()).isFalse();
      assertThat(downstream.config().isAutoRead()).isFalse();

      upstreamFlushBlocker.release(upstream);
      upstream.runPendingTasks();

      assertThat(upstream.isWritable()).isTrue();
      assertThat(downstream.config().isAutoRead()).isEqualTo(initiallyReading);
    } finally {
      upstream.finishAndReleaseAll();
      downstream.finishAndReleaseAll();
    }
  }

  @Test
  public void writabilityChangeAfterResponseIsIgnored() {
    EmbeddedChannel downstream = new EmbeddedChannel();
    EmbeddedChannel upstream =
        new EmbeddedChannel(new ForwardHandler(downstream, proxyMetrics, "upstream"));
    try {
      logs.warnProblems();
      upstream.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
      assertThat((Object) downstream.readOutbound()).isSameAs(LastHttpContent.EMPTY_LAST_CONTENT);

      upstream.pipeline().fireChannelWritabilityChanged();

      assertThat(upstream.isOpen()).isTrue();
      assertThat(upstreamErrors("exception_without_request")).isZero();
      assertThat(logs.messages(ForwardHandler.class)).isEmpty();
    } finally {
      upstream.finishAndReleaseAll();
      downstream.finishAndReleaseAll();
    }
  }

  /// An exception after the response reaches no ingress handler, so it is counted here
  @Test
  public void upstreamExceptionAfterTheResponseIsCounted() {
    EmbeddedChannel upstream =
        new EmbeddedChannel(new ForwardHandler(null, proxyMetrics, "upstream"));
    upstream.pipeline().fireExceptionCaught(new IOException("late failure"));
    assertThat(upstreamErrors("exception_without_request")).isOne();
    assertThat(upstream.isOpen()).isFalse();
    assertThat(logs.errors()).isEmpty();
  }

  double upstreamErrors(String type) {
    return proxyMetrics.upstreamErrorCounter.labelValues("upstream", type).get();
  }

  @Test
  public void proxyHeadersAreAdded() {
    ingressChannel = ingressChannel(new InetSocketAddress("192.0.2.7", 4711));
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().set("X-Forwarded-For", "203.0.113.1");
    ingressChannel.writeInbound(request);
    upstreamChannel.flush();
    HttpRequest forwarded = upstreamChannel.readOutbound();
    assertThat(forwarded.headers().getAll("X-Forwarded-For")).containsExactly("192.0.2.7");
    assertThat(forwarded.headers().get("X-Forwarded-Proto")).isEqualTo("https");
  }

  /// No pipelining, a second request before the response is complete is refused.
  @Test
  public void secondRequestIsRejected() {
    sendGetRequest();
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/2"));
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(400);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.lines()).isNotEmpty();
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(DownstreamHandler.class.getName()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(Level.DEBUG);
              assertThat(e.info().channel()).isSameAs(ingressChannel);
              assertThat(e.message()).contains("another request is unexpected", "phase=waiting");
            });
    assertThat(
            proxyMetrics
                .ingressErrorCounter
                .labelValues(SanitizeHostHandler.MISSING_HOST, "waiting", "another_request")
                .get())
        .isOne();
    assertThat(logs.errors()).isEmpty();
  }

  /// Body content without a request has no upstream to go to.
  @Test
  public void contentWithoutRequestIsReleased() {
    HttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    LastHttpContent last = new DefaultLastHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    ingressChannel.writeInbound(content, last);
    assertThat(content.refCnt()).isZero();
    assertThat(last.refCnt()).isZero();
    assertThat(upstreamChannel).isNull();
  }

  @Test
  public void upstreamConnectFailureIs502() {
    connectFailure = new SocketException("Connection refused");
    HttpContent body = new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1}));
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"),
        body,
        new DefaultLastHttpContent());
    rattle();
    assertThat(body.refCnt()).isZero();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.lines()).anySatisfy(l -> assertThat(l).contains("502"));
    assertThat(requestLogCapture.outcomes())
        .containsExactly(RequestOutcome.UPSTREAM_CONNECT_FAILED);
  }

  @Test
  public void upstreamWriteFailureIs502() {
    failUpstreamWrites = true;
    sendGetRequest();
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(response.status().reasonPhrase()).isEqualTo("Upstream write problem");
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.UPSTREAM_WRITE_FAILED);
    assertThat(
            proxyMetrics
                .requestOutcomeCounter
                .labelValues(
                    SanitizeHostHandler.MISSING_HOST, RequestOutcome.UPSTREAM_WRITE_FAILED.value())
                .getLongValue())
        .isEqualTo(1);
  }

  /// An exception on the upstream connection while waiting for the response ends the ingress
  /// request with a 502 and closes the upstream.
  @Test
  public void upstreamExceptionWhileWaitingIs502() {
    sendGetRequest();
    upstreamChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.lines()).singleElement().asString().contains("Connection reset");
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.EDGE_ERROR);
    assertThat(
            proxyMetrics
                .ingressErrorCounter
                .labelValues(SanitizeHostHandler.MISSING_HOST, "waiting", "other")
                .get())
        .isEqualTo(1);
    assertThat(logs.entries())
        .filteredOn(
            e ->
                e.source().equals(ForwardHandler.class.getName())
                    && e.info().message().startsWith("upstream failure"))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(Level.DEBUG);
              assertThat(e.info().channel()).isSameAs(upstreamChannel);
              assertThat(e.message()).contains("downstream=", "Connection reset");
            });
    assertThat(logs.errors()).isEmpty();
  }

  /// With the production levels, an upstream exception during an exchange gives one `WARNING`: by
  /// the [ExceptionHandler] that counts it, naming its series. The [ForwardHandler] that sees it
  /// first logs it at `DEBUG` only, so one event is not reported at `WARNING` twice. The upstream
  /// close that follows is no `closed_in_flight`, the event is counted once.
  @Test
  public void upstreamExceptionDuringTheExchangeWarnsOnceNamingTheSeries() {
    logs.warnProblems();
    sendGetRequest();
    upstreamChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    rattle();
    assertThat(logs.entries())
        .filteredOn(e -> e.info().level() == Level.WARNING)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.source()).isEqualTo(ExceptionHandler.class.getName());
              assertThat(e.info().message())
                  .endsWith(
                      " (first occurrence counted in sensepitch_ingress_request_errors_total"
                          + "{ingress=\"missing_host\",phase=\"waiting\",type=\"other\"}, further"
                          + " ones are logged at DEBUG)")
                  .doesNotContain("ExceptionHandler");
            });
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(ForwardHandler.class.getName()))
        .extracting(e -> e.info().level())
        .containsOnly(Level.DEBUG);
    assertThat(upstreamErrors("closed_in_flight")).isZero();
  }

  /// A failed connect and a failed write are counted by their outcome. Each is its own kind of
  /// event and warns once, naming its series; the repeated connect failure is at `DEBUG`.
  @Test
  public void upstreamConnectAndWriteFailuresWarnOnTheirOwn() {
    logs.warnProblems();
    connectFailure = new SocketException("Connection refused");
    sendGetRequest();
    rattle();
    ingressChannel = ingressChannel(null);
    sendGetRequest();
    rattle();
    connectFailure = null;
    failUpstreamWrites = true;
    ingressChannel = ingressChannel(null);
    sendGetRequest();
    rattle();
    String connectFailed = "upstream failure, outcome=" + RequestOutcome.UPSTREAM_CONNECT_FAILED;
    String writeFailed = "upstream failure, outcome=" + RequestOutcome.UPSTREAM_WRITE_FAILED;
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(DownstreamHandler.class.getName()))
        .extracting(e -> e.info().level() + " " + e.info().message())
        .satisfiesExactly(
            m ->
                assertThat(m)
                    .startsWith("WARNING " + connectFailed)
                    .endsWith(
                        " (first occurrence counted in sensepitch_ingress_request_outcomes_total"
                            + "{ingress=\"missing_host\",outcome=\"upstream_connect_failed\"}, further ones"
                            + " are logged at DEBUG)"),
            m -> assertThat(m).startsWith("DEBUG " + connectFailed).doesNotContain("first"),
            m ->
                assertThat(m)
                    .startsWith("WARNING " + writeFailed)
                    .endsWith(
                        " (first occurrence counted in sensepitch_ingress_request_outcomes_total"
                            + "{ingress=\"missing_host\",outcome=\"upstream_write_failed\"}, further ones"
                            + " are logged at DEBUG)"));
  }

  @Test
  /// A possible bug of ours is a request level event as well: counted as ingress error `other`,
  /// logged at debug with its stack trace, see [ProxyLogger]
  public void upstreamInternalFailureIsCountedAnd502() {
    sendGetRequest();
    IllegalStateException cause = new IllegalStateException("upstream handler bug");
    upstreamChannel.pipeline().fireExceptionCaught(cause);
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(upstreamChannel.isOpen()).isFalse();
    assertThat(requestLogCapture.lines())
        .singleElement()
        .asString()
        .contains("upstream handler bug");
    assertThat(
            proxyMetrics
                .ingressErrorCounter
                .labelValues(SanitizeHostHandler.MISSING_HOST, "waiting", "other")
                .get())
        .isOne();
    assertThat(logs.errors()).isEmpty();
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(ExceptionHandler.class.getName()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(Level.DEBUG);
              assertThat(e.error()).hasCause(cause);
            });
  }

  @Test
  public void prematureUpstreamClosureIsDebugAndRequestStillReportedOnClientClose() {
    sendGetRequest();
    upstreamChannel.unsafe().close(upstreamChannel.voidPromise());
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(ForwardHandler.class.getName()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.info().level()).isEqualTo(Level.DEBUG);
              assertThat(e.info().channel()).isSameAs(upstreamChannel);
              assertThat(e.message())
                  .contains("request still in flight", "downstream=", "upstream=");
            });
    assertThat(ingressChannel.isOpen()).isTrue();
    assertThat((Object) ingressChannel.readOutbound()).isNull();
    assertThat(upstreamErrors("closed_in_flight")).isOne();
    closeByPeer();
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.CLIENT_ABORTED);
    assertThat(logs.errors()).isEmpty();
  }

  /// A slow client fills the ingress output buffer. Upstream reading is paused and the buffer is
  /// flushed until it drains.
  @Test
  public void slowClientPausesUpstreamAndFlushes() {
    sendGetRequest();
    ingressChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(10, 20));
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
        new DefaultHttpContent(Unpooled.copiedBuffer(new byte[100])));
    rattle();
    // the empty buffers are the flushes DownstreamHandler issues while not writable, reading from
    // upstream was paused meanwhile and is resumed after the buffer drained
    assertThat(ingressChannel.outboundMessages())
        .map(o -> o.getClass().getSimpleName())
        .containsExactly(
            "DefaultHttpResponse", "EmptyByteBuf", "DefaultHttpContent", "EmptyByteBuf");
    assertThat(ingressChannel.isWritable()).isTrue();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
    ingressChannel.finishAndReleaseAll();
  }

  /// When the client goes away, upstream reading continues so the response can be drained. The
  /// close is unexpected and logged once with the phase, the request log has the request as
  /// aborted.
  @Test
  public void ingressInactiveResumesUpstreamReading() {
    sendGetRequest();
    upstreamChannel.config().setAutoRead(false);
    closeByPeer();
    assertThat(upstreamChannel.config().isAutoRead()).isTrue();
    assertThat(logs.messages(DownstreamHandler.class))
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .endsWith(
                        "unexpected close during the exchange, phase=waiting, upstream=connected"));
    assertThat(logs.entries())
        .filteredOn(e -> e.source().equals(DownstreamHandler.class.getName()))
        .allSatisfy(e -> assertThat(e.info().level()).isEqualTo(System.Logger.Level.DEBUG));
    assertThat(requestLogCapture.outcomes()).containsExactly(RequestOutcome.CLIENT_ABORTED);
  }

  /// The client closes during the upload while the upstream is still connecting
  @Test
  public void peerCloseDuringUploadIsUnexpected() {
    deferConnect = true;
    HttpRequest post = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/form");
    HttpUtil.setContentLength(post, 3);
    ingressChannel.writeInbound(post);
    closeByPeer();
    assertThat(logs.messages(DownstreamHandler.class))
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .endsWith(
                        "unexpected close during the exchange, phase=upload, upstream=connecting"));
    pendingConnect.setFailure(new SocketException("Connection refused"));
  }

  /// The client closes while the response is sent
  @Test
  public void peerCloseWhileRespondingIsUnexpected() {
    sendGetRequest();
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    rattle();
    closeByPeer();
    assertThat(logs.messages(DownstreamHandler.class))
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .endsWith(
                        "unexpected close during the exchange, phase=responding,"
                            + " upstream=connected"));
    ingressChannel.finishAndReleaseAll();
  }

  /// The upstream connect failed, but the client closes before the request is complete, so no 502
  /// was sent yet
  @Test
  public void peerCloseAfterConnectFailureIsUnexpected() {
    connectFailure = new SocketException("Connection refused");
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
    closeByPeer();
    assertThat(logs.messages(DownstreamHandler.class))
        .singleElement()
        .satisfies(
            m ->
                assertThat(m)
                    .endsWith(
                        "unexpected close during the exchange, phase=upload,"
                            + " upstream=connect failed"));
  }

  /// A close after the response, e.g. of an idle keep-alive connection, is expected
  @Test
  public void peerCloseAfterResponseIsExpected() {
    sendGetRequest();
    upstreamChannel.writeInbound(
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK),
        new DefaultLastHttpContent());
    rattle();
    closeByPeer();
    assertThat(logs.messages(DownstreamHandler.class)).isEmpty();
    ingressChannel.finishAndReleaseAll();
  }

  /// A close by the proxy during the exchange is expected, whoever requested it logs the reason
  @Test
  public void closeByProxyIsExpected() {
    sendGetRequest();
    ingressChannel.close();
    assertThat(logs.messages(DownstreamHandler.class)).isEmpty();
  }

  /// A connection reset is handled and counted by the [ExceptionHandler], which closes the channel
  @Test
  public void connectionResetIsExpected() {
    sendGetRequest();
    ingressChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    closeByPeer();
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(logs.messages(DownstreamHandler.class)).isEmpty();
  }

  /// An error response closes the connection, e.g. the 502 after a failed upstream connect
  @Test
  public void closeAfterErrorResponseIsExpected() {
    connectFailure = new SocketException("Connection refused");
    sendGetRequest();
    rattle();
    assertThat(ingressChannel.isOpen()).isFalse();
    assertThat(logs.messages(DownstreamHandler.class))
        .noneSatisfy(m -> assertThat(m).startsWith("unexpected close"));
  }

  /// A writability change during the upload after a failed upstream connect has no upstream to
  /// throttle, the exchange goes on until the request is complete and the 502 is sent
  @Test
  public void writabilityChangeAfterConnectFailureIsIgnored() {
    connectFailure = new SocketException("Connection refused");
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
    ingressChannel.pipeline().fireChannelWritabilityChanged();
    assertThat(ingressChannel.isOpen()).isTrue();
    assertThat(logs.errors()).isEmpty();
    ingressChannel.writeInbound(new DefaultLastHttpContent());
    rattle();
    HttpResponse response = ingressChannel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
  }

  /// An exception during the upload after a failed upstream connect reaches the [ExceptionHandler],
  /// which closes the connection
  @Test
  public void exceptionAfterConnectFailureIsHandled() {
    connectFailure = new SocketException("Connection refused");
    ingressChannel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
    ingressChannel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    assertThat(ingressChannel.isOpen()).isFalse();
  }

  /// Closes the ingress channel like the transport does when the peer closes the connection: the
  /// close does not travel through the pipeline, only `channelInactive` is fired.
  private void closeByPeer() {
    ingressChannel.unsafe().close(ingressChannel.voidPromise());
    rattle();
  }

  /// Reads the next outbound message, skipping the empty buffers written to trigger a flush
  @SuppressWarnings("unchecked")
  static <T> T readSkippingEmptyBuffers(EmbeddedChannel channel) {
    Object msg;
    do {
      msg = channel.readOutbound();
    } while (msg instanceof ByteBuf buf && buf.readableBytes() == 0);
    return (T) msg;
  }

  private void sendGetRequest() {
    ingressChannel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        new DefaultLastHttpContent());
  }

  /// If upstream is connected it might put tasks in the into ingress again
  private void rattle() {
    if (upstreamChannel != null) {
      while (ingressChannel.hasPendingTasks() || upstreamChannel.hasPendingTasks()) {
        ingressChannel.runPendingTasks();
        upstreamChannel.runPendingTasks();
      }
    } else {
      ingressChannel.runPendingTasks();
    }
  }

  Channel upstreamChannel(Channel ingressChannel) {
    ChannelHandler forward = new ForwardHandler(ingressChannel, proxyMetrics, "upstream");
    if (failUpstreamWrites) {
      ChannelHandler failWrites =
          new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
              ReferenceCountUtil.release(msg);
              promise.setFailure(new SocketException("Broken pipe"));
            }
          };
      return upstreamChannel = new EmbeddedChannel(failWrites, forward);
    }
    return upstreamChannel = new EmbeddedChannel(upstreamFlushBlocker, forward);
  }

  class MockUpstream implements Upstream {

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      Promise<Channel> promise = downstreamContext.executor().newPromise();
      if (connectFailure != null) {
        promise.setFailure(connectFailure);
        return promise;
      }
      if (deferConnect) {
        return pendingConnect = promise;
      }
      Channel upstreamChannel = upstreamChannel(downstreamContext.channel());
      promise.setSuccess(upstreamChannel);
      return promise;
    }
  }

  /// Holds back flushes while blocked, so written data stays in the outbound buffer and the channel
  /// becomes unwritable, like a peer that does not read fast enough. Placed first in the pipeline,
  /// so it sees the flushes of all other handlers. Water marks used with it need to be well above
  /// the 96 bytes Netty counts per buffer entry, otherwise a single empty flush buffer keeps the
  /// channel unwritable and the flush loops of the handlers never end.
  static class FlushBlocker extends ChannelOutboundHandlerAdapter {

    boolean blocked;

    /// Flushes passed on, see [#holdUnwritable(Channel, int)]
    int flushes;

    /// Flushes left until the channel is writable again, see [#holdUnwritable(Channel, int)]
    private int unwritableFlushes;

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
      if (!blocked) {
        flushes++;
        // writable before passing the last flush on, its listener sees the writable channel
        if (unwritableFlushes > 0 && --unwritableFlushes == 0) {
          ctx.channel().unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        }
        super.flush(ctx);
      }
    }

    /// Keeps the channel unwritable for the next `count` flushes passed on, as if the peer was
    /// slow and the buffer filled again while flushing; then it becomes writable
    void holdUnwritable(Channel channel, int count) {
      unwritableFlushes = count;
      channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
    }

    /// Unblocks and flushes what was held back
    void release(EmbeddedChannel channel) {
      blocked = false;
      channel.flush();
    }
  }
}
