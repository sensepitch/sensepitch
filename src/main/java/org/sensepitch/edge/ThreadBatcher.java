package org.sensepitch.edge;

/// The batch of one thread: encodes request log records into a batch of its own, which it hands
/// to the [HttpBatchSender] when full. Logging a request does not allocate.
///
/// - The thread encodes each record into its `active` batch with the encoder of the payload, see
///   [BatchPayload].
/// - When the batch reaches the batch size, it is handed to the sender, which posts it and
///   returns an empty batch from its pool. The sender also takes partly filled batches when the
///   flush interval is over, see [#takePartial()].
/// - If the sender refuses the handoff, because the batches waiting to be sent reached the
///   limit, the thread keeps its full batch and drops new records until the sender has room. So
///   the newest records are dropped, the waiting ones are kept.
/// - The monitor of this object guards `active`. Only the sender's flush timer competes for it,
///   once per flush interval.
/// - The thread closes its batcher when it ends, or when a reload asks it to, see [#close()]. This
///   is the end marker the sender waits for.
///
/// @author Jens Wilke
final class ThreadBatcher<B extends Batch> {

  private final HttpBatchSender<B> sender;
  private final Thread owner;
  private final RecordEncoder<B> encoder;
  private final int batchBytes;

  /// `null` before the first record, after a partial batch was taken, and after [#close()]. A
  /// batch is taken by [#log] only, unless closed, so a batcher refused by a finishing sender
  /// never holds one, see [HttpBatchSender#newBatcher()].
  private B active;

  private boolean closed;

  ThreadBatcher(HttpBatchSender<B> sender, Thread owner, RecordEncoder<B> encoder) {
    this.sender = sender;
    this.owner = owner;
    this.encoder = encoder;
    this.batchBytes = sender.batchBytes();
  }

  Thread owner() {
    return owner;
  }

  /// Encodes the record into the active batch.
  ///
  /// @return `false` if this batcher is closed, the caller counts the record as dropped.
  ///   A record dropped because the waiting batches reached the limit is counted here and returns
  ///   `true`, it is handled.
  synchronized boolean log(RequestLogInfo info) {
    if (closed) {
      return false;
    }
    if (active == null) {
      active = sender.emptyBatch();
    } else if (active.size() >= batchBytes && !handOff()) {
      // no room for waiting batches, drop the newest record
      sender.countDropped(1);
      return true;
    }
    encoder.encode(active, info);
    active.added();
    if (active.size() >= batchBytes) {
      handOff();
    }
    return true;
  }

  /// Hands the active batch to the sender and continues with the empty batch it returns.
  ///
  /// @return true, if the thread has an empty batch now
  private boolean handOff() {
    B next = sender.handOff(active);
    if (next == null) {
      return false;
    }
    active = next;
    return true;
  }

  /// Called by the sender when the flush interval is over: takes a partly filled batch, if the
  /// waiting batches have room for it. The thread takes a new batch with its next record.
  ///
  /// @return the batch, already counted as waiting, or `null`
  synchronized B takePartial() {
    // a closed batcher has no active batch
    if (active == null || active.records == 0 || !sender.reserve(active)) {
      return null;
    }
    B partial = active;
    active = null;
    return partial;
  }

  /// Records in the active batch, for the queue length metric
  synchronized int records() {
    return active != null ? active.records : 0;
  }

  /// The end marker: no more records are accepted, the last batch goes to the sender. Called by
  /// the owner thread when it ends or when a reload replaced the destination, see
  /// [BatchingRequestLogger#endThread()], or by the sender for a thread that terminated without.
  /// The last batch is counted as waiting before it leaves, so the sender does not stop before
  /// it is sent. Can be called more than once.
  void close() {
    B last;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      last = active;
      active = null;
      if (last != null && last.records > 0) {
        sender.forceReserve(last);
      } else {
        last = null;
      }
    }
    sender.closed(this, last);
  }
}
