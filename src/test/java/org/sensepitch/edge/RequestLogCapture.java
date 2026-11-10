package org.sensepitch.edge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// In-memory destination of the request log, which otherwise goes to standard out. Pass
/// [#stream()] to [Proxy#Proxy(ProxyConfig, PrintStream)] or use [#plainText()] directly in a
/// handler pipeline.
///
/// @author Jens Wilke
public class RequestLogCapture {

  // ByteArrayOutputStream is synchronized, so event loop threads can write concurrently
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

  public PrintStream stream() {
    return stream;
  }

  /// Request logger writing the plain text format into this capture
  public RequestLogger plainText() {
    return new PlainTextRequestLogger(stream);
  }

  /// Request log lines written so far
  public List<String> lines() {
    return buffer.toString(StandardCharsets.UTF_8).lines().toList();
  }
}
