package org.sensepitch.edge;

/// Logs requests via OTLP, see [OtlpLogDestination] and [BatchingRequestLogger].
///
/// @author Jens Wilke
public class OtlpRequestLogger extends BatchingRequestLogger<OtlpBatch> {

  public OtlpRequestLogger(OtlpLogDestination destination) {
    super(destination);
  }
}
