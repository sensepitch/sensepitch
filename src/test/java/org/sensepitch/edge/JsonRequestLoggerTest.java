package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;

/// The JSON request log, the default format, written for every request
///
/// @author Jens Wilke
class JsonRequestLoggerTest {

  final RequestLogCapture capture = new RequestLogCapture();
  final JsonRequestLogger logger = new JsonRequestLogger(capture);

  JsonNode log(TestRequestLogInfo info) throws Exception {
    logger.logRequest(info);
    assertThat(capture.lines()).hasSize(1);
    return new ObjectMapper().readTree(capture.lines().getFirst());
  }

  /// Browsers send the language and client hints, they are grouped below `headers`.
  @Test
  void clientHintsAndLanguage() throws Exception {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "de-DE,de;q=0.9");
    info.request().headers().set(JsonRequestLogger.SEC_CH_UA, "\"Chromium\";v=\"130\"");
    info.request().headers().set(JsonRequestLogger.SEC_CH_UA_PLATFORM, "\"Linux\"");
    info.request().headers().set(JsonRequestLogger.SEC_CH_UA_MOBILE, "?0");
    JsonNode headers = log(info).get("headers");
    assertThat(headers.get("accept_language").asText()).isEqualTo("de-DE,de;q=0.9");
    assertThat(headers.get("sec_ch_ua").asText()).isEqualTo("\"Chromium\";v=\"130\"");
    assertThat(headers.get("sec_ch_ua_platform").asText()).isEqualTo("\"Linux\"");
    assertThat(headers.get("sec_ch_ua_mobile").asText()).isEqualTo("?0");
  }

  /// Only the headers present are written, e.g. Firefox and Safari send no client hints.
  @Test
  void onlyPresentHeaders() throws Exception {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "en");
    JsonNode headers = log(info).get("headers");
    assertThat(headers.size()).isEqualTo(1);
    assertThat(headers.get("accept_language").asText()).isEqualTo("en");
  }

  /// Bots and scripts typically send none, the object is left out.
  @Test
  void noHeadersObjectWithoutHints() throws Exception {
    JsonNode record = log(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(record.has("headers")).isFalse();
    assertThat(record.get("header_names").toString()).isEqualTo("[\"host\"]");
  }

  @Test
  void commonFields() throws Exception {
    JsonNode record =
        log(
            TestRequestLogInfo.of(HttpMethod.POST, 201)
                .withChannel(TestChannels.channelFrom("192.0.2.7")));
    assertThat(record.get("type").asText()).isEqualTo(JsonRequestLogger.TYPE);
    assertThat(record.get("host").asText()).isEqualTo("example.com");
    assertThat(record.get("remote_addr").asText()).isEqualTo("192.0.2.7");
    assertThat(record.get("method").asText()).isEqualTo("POST");
    assertThat(record.get("status").asInt()).isEqualTo(201);
    assertThat(record.get("bytes_sent").asLong()).isEqualTo(100);
    assertThat(record.get("total_nanos").asLong()).isEqualTo(3_000);
    assertThat(record.get("cookie").asBoolean()).isFalse();
    assertThat(record.has("error")).isFalse();
  }

  /// Without a socket, e.g. a request that was never received, there is no remote address.
  @Test
  void noRemoteAddressWithoutSocket() throws Exception {
    assertThat(log(TestRequestLogInfo.of(HttpMethod.GET, 200)).has("remote_addr")).isFalse();
  }

  @Test
  void errorMessage() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 502)
            .withError(new IllegalStateException("upstream gone"));
    assertThat(log(info).get("error").asText()).isEqualTo("upstream gone");
  }

  /// An exception without message is identified by its class name.
  @Test
  void errorWithoutMessage() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 502).withError(new IllegalStateException());
    assertThat(log(info).get("error").asText()).isEqualTo("IllegalStateException");
  }

  /// A server error without exception, e.g. a timeout response, logs the reason phrase.
  @Test
  void serverErrorWithoutException() throws Exception {
    assertThat(log(TestRequestLogInfo.of(HttpMethod.GET, 504)).get("error").asText())
        .isEqualTo("Gateway Timeout");
  }
}
