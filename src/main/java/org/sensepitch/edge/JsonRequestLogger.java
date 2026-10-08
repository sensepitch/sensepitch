package org.sensepitch.edge;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/// Writes one JSON object per request, separated by newlines. Every record carries the property
/// `type` with the value [#TYPE], so a log collector reading standard out can tell request logs
/// apart from other log sources.
///
/// Properties without a value are left out instead of being written as a placeholder. The client
/// hint request headers are grouped below the property `headers`. The cookie header is reported as
/// the boolean property `cookie`, because only its presence is of interest, not its content.
///
/// The record is rendered into a buffer and written with a single [PrintStream#println(String)]
/// call, so records of concurrent event loops cannot interleave within one line.
public class JsonRequestLogger implements RequestLogger {

  /// Value of the `type` property identifying this log source
  public static final String TYPE = "request";

  static final String SEC_CH_UA = "sec-ch-ua";
  static final String SEC_CH_UA_PLATFORM = "sec-ch-ua-platform";
  static final String SEC_CH_UA_MOBILE = "sec-ch-ua-mobile";
  static final String SIGNATURE_AGENT = "Signature-Agent";

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private static final ProxyLogger DEBUG = ProxyLogger.get(JsonRequestLogger.class);

  private final JsonFactory jsonFactory = new JsonFactory();
  private final PrintStream output;

  public JsonRequestLogger() {
    this(System.out);
  }

  public JsonRequestLogger(PrintStream output) {
    this.output = output;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    StringWriter buffer = new StringWriter(512);
    try (JsonGenerator json = jsonFactory.createGenerator(buffer)) {
      writeRecord(json, info);
    } catch (IOException e) {
      // a StringWriter does not do I/O, so this cannot happen in practice
      DEBUG.error(info.channel(), "Error rendering request log record", e);
      return;
    }
    output.println(buffer.toString());
  }

  private void writeRecord(JsonGenerator json, RequestLogInfo info) throws IOException {
    HttpRequest request = info.request();
    HttpResponse response = info.response();
    json.writeStartObject();
    json.writeStringField("type", TYPE);
    json.writeStringField(
        "time", TIMESTAMP.format(Instant.ofEpochMilli(info.requestStartTimeMillis())));
    json.writeStringField("request_id", info.requestId());
    writeOptional(json, "host", request.headers().get(HttpHeaderNames.HOST));
    writeOptional(json, "remote_addr", remoteAddress(info));
    json.writeStringField("method", request.method().name());
    json.writeStringField("uri", request.uri());
    json.writeStringField("protocol", request.protocolVersion().text());
    json.writeNumberField("status", response.status().code());
    json.writeNumberField("content_bytes", info.contentBytes());
    json.writeNumberField("bytes_received", info.bytesReceived());
    json.writeNumberField("bytes_sent", info.bytesSent());
    json.writeNumberField("receive_nanos", info.receiveDurationNanos());
    json.writeNumberField("response_nanos", info.responseTimeNanos());
    json.writeNumberField("total_nanos", info.totalDurationNanos());
    json.writeBooleanField("cookie", request.headers().contains(HttpHeaderNames.COOKIE));
    writeHeaders(json, request);
    writeHeaderNames(json, request);
    writeOptional(json, "ip_traits", IpTraitsHandler.extract(request));
    writeOptional(
        json, "admission_token", request.headers().get(Deflector.ADMISSION_TOKEN_HEADER));
    writeOptional(json, "bypass", request.headers().get(BypassCheck.HEADER));
    writeOptional(json, "user_agent", request.headers().get(HttpHeaderNames.USER_AGENT));
    writeOptional(json, "referer", request.headers().get(HttpHeaderNames.REFERER));
    writeOptional(json, "signature_agent", request.headers().get(SIGNATURE_AGENT));
    writeOptional(json, "error", error(info, response));
    json.writeEndObject();
  }

  /// Client hints and the language preference, grouped below `headers`. The object is left out
  /// completely when the client sent none of them.
  private void writeHeaders(JsonGenerator json, HttpRequest request) throws IOException {
    String acceptLanguage = request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE);
    String secChUa = request.headers().get(SEC_CH_UA);
    String secChUaPlatform = request.headers().get(SEC_CH_UA_PLATFORM);
    String secChUaMobile = request.headers().get(SEC_CH_UA_MOBILE);
    if (acceptLanguage == null
        && secChUa == null
        && secChUaPlatform == null
        && secChUaMobile == null) {
      return;
    }
    json.writeObjectFieldStart("headers");
    writeOptional(json, "accept_language", acceptLanguage);
    writeOptional(json, "sec_ch_ua", secChUa);
    writeOptional(json, "sec_ch_ua_platform", secChUaPlatform);
    writeOptional(json, "sec_ch_ua_mobile", secChUaMobile);
    json.writeEndObject();
  }

  /// All header names the client sent, in the order received. The names alone already identify a
  /// client, while the values may contain personal data.
  private void writeHeaderNames(JsonGenerator json, HttpRequest request) throws IOException {
    json.writeArrayFieldStart("header_names");
    for (var entry : request.headers()) {
      json.writeString(entry.getKey());
    }
    json.writeEndArray();
  }

  private String remoteAddress(RequestLogInfo info) {
    if (info.channel() != null
        && info.channel().remoteAddress() instanceof InetSocketAddress address) {
      return address.getAddress().getHostAddress();
    }
    return null;
  }

  private String error(RequestLogInfo info, HttpResponse response) {
    Throwable error = info.error();
    if (error != null) {
      return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
    if (response.status().code() >= 500) {
      return response.status().reasonPhrase();
    }
    return null;
  }

  private void writeOptional(JsonGenerator json, String field, String value) throws IOException {
    if (value != null) {
      json.writeStringField(field, value);
    }
  }
}
