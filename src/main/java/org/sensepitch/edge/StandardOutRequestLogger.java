package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * @author Jens Wilke
 */
public class StandardOutRequestLogger implements RequestLogger {

  private static final DateTimeFormatter CLF_TIME =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

  @Override
  public void logRequest(RequestLogInfo info) {
    HttpRequest request = info.request();
    HttpResponse response = info.response();
    String remoteHost = "-";
    if (info.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) info.channel().remoteAddress();
      remoteHost = addr.getAddress().getHostAddress();
    }
    String admissionToken = request.headers().get(DeflectorHandler.ADMISSION_TOKEN_HEADER);
    if (admissionToken == null) {
      admissionToken = "-";
    }
    String time = ZonedDateTime.now().format(CLF_TIME);
    String requestLine = request.method() + " " + request.uri() + " " + request.protocolVersion();
    String referer = sanitize(request.headers().get(HttpHeaderNames.REFERER));
    String ua = sanitize(request.headers().get(HttpHeaderNames.USER_AGENT));
    String bypass = sanitize(request.headers().get(BypassCheck.HEADER));
    String host = sanitize(request.headers().get(HttpHeaderNames.HOST));
    int status = response.status().code();
    String timing =
        formatDeltaTime(info.receiveDurationNanos())
            + "<"
            + formatDeltaTime(info.responseTimeNanos())
            + "="
            + formatDeltaTime(info.totalDurationNanos());
    String ipTraits = sanitize(IpTraitsHandler.extract(request));
    String error = null;
    if (info.error() != null) {
      error = sanitize(info.error().getMessage());
    }
    if (error == null && status >= 500) {
      error = sanitize(response.status().reasonPhrase());
    }
    if (error == null) {
      error = "-";
    }
    String signatureAgent = request.headers().get("Signature-Agent");
    if (signatureAgent == null) {
      signatureAgent = "-";
    }
    StringBuilder sb = new StringBuilder();
    request
        .headers()
        .forEach(
            kv -> {
              if (sb.length() > 0) {
                sb.append(", ");
              }
              sb.append(sanitize(kv.getKey()));
            });
    String headerNames = sb.toString();
    System.out.println(
        "RQ0 "
            + info.requestId()
            + " "
            + host
            + " "
            + remoteHost
            + " \""
            + ipTraits
            + "\" "
            + admissionToken
            + " ["
            + time
            + "] "
            + requestLine
            + " "
            + status
            + " "
            + info.contentBytes()
            + " "
            + info.bytesReceived()
            + ">"
            + info.bytesSent()
            + " "
            + timing
            + " \""
            + bypass
            + "\" \""
            + ua
            + "\" "
            + referer
            + " \""
            + error
            + "\""
            + " \""
            + signatureAgent
            + "\""
            + " \""
            + headerNames
            + "\"");
  }

  String formatDeltaTime(long nanoDelta) {
    long millisDelta = nanoDelta / 1000 / 1000;
    if (millisDelta == 0) {
      return "0";
    }
    // pattern "0.000" → at least one digit before the dot, exactly three after
    DecimalFormat df = new DecimalFormat("0.000");
    return df.format(millisDelta / 1000.0);
  }

  String sanitize(String s) {
    if (s == null) {
      return "-";
    }
    if (s.indexOf('"') >= 0) {
      return s.replace("\"", "\\");
    }
    return s;
  }
}
