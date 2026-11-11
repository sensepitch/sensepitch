package org.sensepitch.edge;

import io.netty.util.concurrent.FastThreadLocal;

/// Logs requests via OTLP. Each thread gets a logger of its own, see [OtlpThreadLogger], which
/// encodes the record into a buffer of that thread, so logging a request does not allocate. The
/// [OtlpLogDestination] collects and sends the buffers.
///
/// The event loops are Netty threads, for them the thread local lookup is an array access.
///
/// @author Jens Wilke
public class OtlpRequestLogger implements RequestLogger {

  private final OtlpLogDestination destination;

  private final FastThreadLocal<OtlpThreadLogger> threadLoggers =
      new FastThreadLocal<>() {
        @Override
        protected OtlpThreadLogger initialValue() {
          return destination.newThreadLogger();
        }
      };

  public OtlpRequestLogger(OtlpLogDestination destination) {
    this.destination = destination;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    threadLoggers.get().log(info);
  }

  /// Sends the buffered records and stops the destination, see [OtlpLogDestination#close()]
  @Override
  public void close() {
    destination.close();
  }
}
