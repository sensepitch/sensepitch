package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;

/// The legacy single line request log, see [RequestLogConfig#plainText()]
///
/// @author Jens Wilke
class PlainTextRequestLoggerTest {

  final RequestLogCapture capture = new RequestLogCapture();
  final PlainTextRequestLogger logger = new PlainTextRequestLogger(capture);

  String log(TestRequestLogInfo info) {
    logger.logRequest(info);
    assertThat(capture.lines()).hasSize(1);
    return capture.lines().getFirst();
  }

  @Test
  void clientAddress() {
    String line = log(TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7"));
    assertThat(line).startsWith("RQ0 0 example.com 192.0.2.7 ");
  }

  @Test
  void noClientAddressWithoutSocket() {
    String line = log(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(line).startsWith("RQ0 0 example.com - ");
  }

  /// Values are written in double quotes, a quote sent by the client must not end the field early,
  /// otherwise a client could inject fields into the log line. Quotes are escaped as `\"`.
  @Test
  void quotesInValuesAreEscaped() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "evil\" 200 \"injected");
    assertThat(log(info)).contains(" \"evil\\\" 200 \\\"injected\" ");
  }

  /// A trailing backslash must not escape the closing quote, so backslashes are escaped as well.
  @Test
  void backslashesAreEscaped() {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "agent\\");
    assertThat(log(info)).contains(" \"agent\\\\\" ");
  }

  @Test
  void serverErrorAndHeaderNames() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 502)
            .withError(new IllegalStateException("upstream gone"));
    info.request().headers().set(HttpHeaderNames.USER_AGENT, "curl");
    String line = log(info);
    assertThat(line).contains(" 502 ").contains("upstream gone").contains("host, user-agent");
  }
}
