package org.sensepitch.edge;

import io.netty.util.concurrent.FastThreadLocal;

/// Passes the request log data on with an anonymized client address, see
/// [AnonymizedClientRequestLogInfo]. Wraps the logger of a destination with
/// [RequestLogDestinationConfig#anonymizeClientIp()] set, so the formats themselves contain no
/// anonymization code.
///
/// Each thread reuses one wrapper, the data is only valid during the call anyway, so wrapping does
/// not allocate.
///
/// @author Jens Wilke
public class AnonymizingRequestLogger implements RequestLogger {

  private final RequestLogger delegate;

  private final FastThreadLocal<AnonymizedClientRequestLogInfo> wrappers =
      new FastThreadLocal<>() {
        @Override
        protected AnonymizedClientRequestLogInfo initialValue() {
          return new AnonymizedClientRequestLogInfo(null);
        }
      };

  public AnonymizingRequestLogger(RequestLogger delegate) {
    this.delegate = delegate;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    AnonymizedClientRequestLogInfo wrapper = wrappers.get();
    try {
      delegate.logRequest(wrapper.wrap(info));
    } finally {
      // do not keep the request of a connection that may be gone
      wrapper.wrap(null);
    }
  }

  @Override
  public void close() {
    delegate.close();
  }
}
