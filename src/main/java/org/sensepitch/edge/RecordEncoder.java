package org.sensepitch.edge;

/// Encodes request log records into a batch. Holds reusable state, so an instance is used by one
/// thread at a time, see [BatchPayload#newEncoder()].
///
/// @author Jens Wilke
interface RecordEncoder<B extends Batch> {

  /// Appends the record to the batch. On an exception, the batch is left as it was, without a
  /// partial record. Does not count the record, see [Batch#added()].
  void encode(B batch, RequestLogInfo info);
}
