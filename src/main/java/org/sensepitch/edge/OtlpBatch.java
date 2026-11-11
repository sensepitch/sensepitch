package org.sensepitch.edge;

/// A buffer of encoded OTLP records, each framed as a `ScopeLogs.log_records` entry, so the buffer
/// can be sent as it is. Filled by one [OtlpThreadLogger], then queued and sent by the
/// [OtlpLogDestination] and reused afterwards.
///
/// @author Jens Wilke
final class OtlpBatch {

  /// Room beyond the batch size for the record that crosses it, so a buffer does not grow
  static final int SLACK = 16 * 1024;

  final ProtobufWriter writer;
  int records;

  OtlpBatch(int batchBytes) {
    writer = new ProtobufWriter(batchBytes + SLACK);
  }

  int size() {
    return writer.size();
  }

  void reset() {
    writer.reset();
    records = 0;
  }
}
