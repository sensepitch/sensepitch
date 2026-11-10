package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.List;

/// In-memory destination of the request log, which otherwise goes to standard out. Pass it to
/// [Proxy#Proxy(ProxyConfig, RequestLogOutput)] or use [#plainText()] directly in a handler
/// pipeline.
///
/// @author Jens Wilke
public class RequestLogCapture implements RequestLogOutput {

  private final List<String> lines = new ArrayList<>();

  @Override
  public void writeLine(String line) {
    // event loop threads may write concurrently
    synchronized (lines) {
      lines.add(line);
    }
  }

  /// Request logger writing the plain text format into this capture
  public RequestLogger plainText() {
    return new PlainTextRequestLogger(this);
  }

  /// Request log lines written so far
  public List<String> lines() {
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }
}
