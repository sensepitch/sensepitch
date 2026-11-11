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
  private final List<RequestOutcome> outcomes = new ArrayList<>();

  @Override
  public void writeLine(String line) {
    // event loop threads may write concurrently
    synchronized (lines) {
      lines.add(line);
    }
  }

  /// Request logger writing the plain text format into this capture. It also records the outcome
  /// of each request, which the plain text format does not show, see [#outcomes()].
  public RequestLogger plainText() {
    PlainTextRequestLogger text = new PlainTextRequestLogger(this);
    return info -> {
      synchronized (lines) {
        outcomes.add(info.outcome());
      }
      text.logRequest(info);
    };
  }

  /// Outcomes of the requests logged via [#plainText()] so far
  public List<RequestOutcome> outcomes() {
    synchronized (lines) {
      return List.copyOf(outcomes);
    }
  }

  /// Request log lines written so far
  public List<String> lines() {
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }
}
