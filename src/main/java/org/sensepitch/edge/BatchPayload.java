package org.sensepitch.edge;

import java.util.List;

/// The payload of a destination that posts batches of request log records via HTTP, e.g.
/// OTLP protobuf, see [OtlpBatchPayload]. The posting itself is generic, see [HttpBatchSender].
///
/// @author Jens Wilke
interface BatchPayload<B extends Batch> {

  /// An empty batch for `batchBytes` of records, plus room for the record that crosses the size
  B newBatch(int batchBytes);

  /// An encoder for one thread, see [ThreadBatcher]
  RecordEncoder<B> newEncoder();

  /// Writes the body of one request: the records of all batches, in their order, within the
  /// enclosing message of the payload. The parts are passed to the sink as they are, e.g. the
  /// arrays of the batches, without copying; they must not be changed until the request completed.
  void writeBody(List<B> batches, BodySink sink);

  /// Receives the parts of a body, see [#writeBody(List, BodySink)]
  interface BodySink {

    void write(byte[] bytes, int offset, int length);
  }

  /// Value of the `Content-Type` header
  String contentType();
}
