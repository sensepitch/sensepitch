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

  /// Logger of the destination that replaced this one in a configuration reload, gets the records
  /// logged into this one after the switch, see [#handOverTo]
  private volatile RequestLogger successor;

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
    if (threadLoggers.get().log(info)) {
      return;
    }
    // closed, e.g. an event loop still had the destinations before a reload switched them
    RequestLogger next = successor;
    if (next != null) {
      next.logRequest(info);
    } else {
      destination.countDropped(1);
    }
  }

  /// Hands over to the destination that replaces this one in a configuration reload: its
  /// buffered records and the records still logged into this one, so a reload loses nothing.
  ///
  /// @param next the replacing destination
  /// @param nextLogger the top level logger of the replacing destination, including its
  ///   anonymization, it gets the records logged into this one from now on
  void handOverTo(OtlpRequestLogger next, RequestLogger nextLogger) {
    // set first, so a record refused once the destination is closed finds it
    successor = nextLogger;
    destination.handOverTo(next.destination);
  }

  /// Sends the buffered records and stops the destination, see [OtlpLogDestination#close()]
  @Override
  public void close() {
    destination.close();
  }
}
