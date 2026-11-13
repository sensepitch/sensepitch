package org.sensepitch.edge;

/// A buffer of encoded records, filled by one [ThreadBatcher], posted by the [HttpBatchSender] and
/// reused afterwards. The payload decides how the records are encoded and framed, see
/// [BatchPayload].
///
/// @author Jens Wilke
abstract class Batch {

  /// Records in the batch
  int records;

  /// [System#nanoTime()] of the first record, the age of the batch, meaningful if `records > 0`
  long firstRecordNanos;

  /// Bytes of the encoded records
  abstract int size();

  /// Removes the encoded records
  abstract void clear();

  /// Counts a record that was encoded into the batch
  final void added() {
    if (records++ == 0) {
      firstRecordNanos = System.nanoTime();
    }
  }

  /// Empties the batch for reuse
  final void reset() {
    clear();
    records = 0;
    firstRecordNanos = 0;
  }
}
