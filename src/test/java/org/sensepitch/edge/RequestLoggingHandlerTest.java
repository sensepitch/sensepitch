package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// A request log record that fails is a request level event: counted, logged at debug, see
/// [ProxyLogger], and the request outcome is counted before.
class RequestLoggingHandlerTest {

  @RegisterExtension LogCapture logs = new LogCapture();

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
}
