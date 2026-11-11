package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslClosedEngineException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Classification of ingress exceptions by phase and type, and the reaction to them.
///
/// @author Jens Wilke
public class ExceptionHandlerTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics metrics = new ProxyMetrics();
  ExceptionHandler handler = new ExceptionHandler(metrics);
  RequestLogCapture requestLog = new RequestLogCapture();

  EmbeddedChannel beforeHandshake() {
    return new EmbeddedChannel(
        new RequestLoggingHandler(new ProxyMetrics(), requestLog.plainText()), handler);
  }

  EmbeddedChannel afterHandshake() {
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new TimeoutsTest.FakeSslHandler(),
            new RequestLoggingHandler(new ProxyMetrics(), requestLog.plainText()),
            handler);
    channel.runPendingTasks();
    return channel;
  }

  double count(String phase, String type) {
    return metrics.ingressErrorCounter.labelValues(phase, type).get();
  }

  @Test
  public void handshakeSslError() {
    EmbeddedChannel channel = beforeHandshake();
    channel.pipeline().fireExceptionCaught(new DecoderException(new NotSslRecordException()));
    assertThat(channel.isOpen()).isFalse();
    handler = new ExceptionHandler(metrics);
    beforeHandshake()
        .pipeline()
        .fireExceptionCaught(new DecoderException(new SSLHandshakeException("x")));
    assertThat(count("handshake", "ssl")).isEqualTo(2);
    assertThat(logs.messages(ExceptionHandler.class)).isEmpty();
  }

  @Test
  public void handshakeReset() {
    EmbeddedChannel channel = beforeHandshake();
    channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));
    assertThat(count("handshake", "reset")).isEqualTo(1);
    assertThat(channel.isOpen()).isFalse();
    assertThat(logs.messages(ExceptionHandler.class)).isEmpty();
  }

  @Test
  public void handshakeOther() {
    EmbeddedChannel channel = beforeHandshake();
    channel.pipeline().fireExceptionCaught(new DecoderException(new IllegalStateException()));
    assertThat(count("handshake", "other")).isEqualTo(1);
    assertThat(channel.isOpen()).isFalse();
    assertThat(logs.messages(ExceptionHandler.class))
        .singleElement()
        .asString()
        .contains("handshake error");
  }

  /// A reset is counted only, there is nobody to respond to.
  @Test
  public void resetWhileRequesting() {
    EmbeddedChannel channel = afterHandshake();
    channel.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));
    assertThat(count("requesting", "reset")).isEqualTo(1);
    assertThat(channel.isOpen()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(logs.messages(ExceptionHandler.class)).isEmpty();
  }

  /// Without a request there is nothing to respond to, so the error is logged.
  @Test
  public void otherWhileRequesting() {
    EmbeddedChannel channel = afterHandshake();
    channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));
    assertThat(count("requesting", "other")).isEqualTo(1);
    assertThat(channel.isOpen()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(logs.messages(ExceptionHandler.class))
        .singleElement()
        .asString()
        .contains("phase=requesting");
  }

  @Test
  public void resetWhileUploading() {
    EmbeddedChannel channel = afterHandshake();
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
    channel.pipeline().fireExceptionCaught(new IOException("Broken pipe"));
    assertThat(count("upload", "reset")).isEqualTo(1);
    assertThat(channel.isOpen()).isFalse();
  }

  /// The request is known but no response sent yet, so the client gets a 502 and the request is
  /// logged with the exception.
  @Test
  public void otherWhileWaitingIs502() {
    EmbeddedChannel channel = afterHandshake();
    channel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        LastHttpContent.EMPTY_LAST_CONTENT);
    channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));
    assertThat(count("waiting", "other")).isEqualTo(1);
    HttpResponse response = channel.readOutbound();
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(channel.isOpen()).isFalse();
    assertThat(requestLog.lines()).singleElement().asString().contains("502");
    assertThat(requestLog.outcomes()).containsExactly(RequestOutcome.EDGE_ERROR);
  }

  /// Exception details are for the log, they must not be sent to the client.
  @Test
  public void exceptionDetailsAreNotSentToTheClient() {
    EmbeddedChannel channel = afterHandshake();
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/"));
    channel
        .pipeline()
        .fireExceptionCaught(new SocketException("Connection refused: /10.1.2.3:8080\r\nX: y"));
    HttpResponse response = channel.readOutbound();
    assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_GATEWAY);
    assertThat(response.status().reasonPhrase()).doesNotContain("10.1.2.3");
    assertThat(channel.isOpen()).isFalse();
  }

  /// Once the response has started, the only option left is to close the connection.
  @Test
  public void otherWhileRespondingIsLoggedAndClosed() {
    EmbeddedChannel channel = afterHandshake();
    channel.writeInbound(
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
        LastHttpContent.EMPTY_LAST_CONTENT);
    channel.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    channel.readOutbound();
    channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));
    assertThat(count("responding", "other")).isEqualTo(1);
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isOpen()).isFalse();
    assertThat(logs.messages(ExceptionHandler.class))
        .singleElement()
        .asString()
        .contains("phase=responding");
  }

  @Test
  public void isConnectionReset() {
    assertThat(handler.isConnectionReset(new SslClosedEngineException("closed"))).isTrue();
    assertThat(handler.isConnectionReset(new ClosedChannelException())).isTrue();
    assertThat(handler.isConnectionReset(new IOException("Connection reset by peer"))).isTrue();
    assertThat(handler.isConnectionReset(new IOException("Broken pipe"))).isTrue();
    assertThat(handler.isConnectionReset(new SocketException("Connection reset"))).isTrue();
    assertThat(handler.isConnectionReset(new IOException((String) null))).isFalse();
    assertThat(handler.isConnectionReset(new IOException("Disk full"))).isFalse();
    assertThat(handler.isConnectionReset(new RuntimeException("Connection reset"))).isFalse();
  }

  @Test
  public void isSslDecoderException() {
    assertThat(handler.isSslDecoderException(new DecoderException(new NotSslRecordException())))
        .isTrue();
    assertThat(handler.isSslDecoderException(new DecoderException(new SSLHandshakeException("x"))))
        .isTrue();
    assertThat(handler.isSslDecoderException(new DecoderException(new IOException()))).isFalse();
    assertThat(handler.isSslDecoderException(new SSLHandshakeException("not wrapped"))).isFalse();
  }
}
