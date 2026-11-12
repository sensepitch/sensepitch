package org.sensepitch.edge;

import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_LOG_RECORDS;

/// The OTLP request logger of one thread: encodes records into a batch of its own, which it hands
/// to the [OtlpLogDestination] when full. Logging a request does not allocate.
///
/// - The thread encodes each record into its `active` batch, already framed as a
///   `ScopeLogs.log_records` entry, so the destination can send the buffer as it is.
/// - When the batch reaches the batch size, it is handed to the destination, which queues it and
///   returns an empty batch from its pool. The destination also takes partly filled batches
///   when the flush interval is over, see [#handOffPartial()].
/// - If the destination refuses the handoff, because the batches waiting to be sent reached the
///   limit, the thread keeps its full batch and drops new records until the destination has room.
///   So the newest records are dropped, the waiting ones are kept.
/// - The monitor of this object guards `active`. Only the destination competes for it, once
///   per flush interval. Lock order: this object first, then the destination.
///
/// @author Jens Wilke
final class OtlpThreadLogger {

  private final OtlpLogDestination destination;
  private final Thread owner;
  private final OtlpRequestLogSink sink;
  private final int batchBytes;

  /// `null` after [#retire()], or if no batch was available
  private OtlpBatch active;

  private boolean retired;

  OtlpThreadLogger(OtlpLogDestination destination, Thread owner, OtlpBatch first) {
    this.destination = destination;
    this.owner = owner;
    this.batchBytes = destination.batchBytes();
    this.sink = new OtlpRequestLogSink(destination.sampleHeadersInterval());
    this.active = first;
  }

  Thread owner() {
    return owner;
  }

  /// Encodes the record into the active batch.
  ///
  /// @return `false` if this logger is retired or its destination closed, the caller passes the
  ///   record on to a successor. A record dropped because the waiting batches reached the limit
  ///   is counted here and returns `true`, it is handled.
  boolean log(RequestLogInfo info) {
    boolean handedOff;
    synchronized (this) {
      if (retired || destination.isClosed()) {
        return false;
      }
      if ((active == null || active.size() >= batchBytes) && !handOff()) {
        // no room for waiting batches, drop the newest record
        destination.countDropped(1);
        return true;
      }
      ProtobufWriter out = active.writer;
      int mark = out.size();
      int record = out.startMessage(SCOPE_LOGS_LOG_RECORDS);
      try {
        sink.encode(out, info);
      } catch (RuntimeException e) {
        // leave no partial record behind
        out.truncate(mark);
        throw e;
      }
      out.endMessage(record);
      active.records++;
      handedOff = active.size() >= batchBytes && handOff();
    }
    // outside the lock, the destination takes it right away
    if (handedOff) {
      destination.wake();
    }
    return true;
  }

  /// Hands a non-empty active batch to the destination and continues with the batch it returns.
  ///
  /// @return true, if the thread has an empty batch now
  private boolean handOff() {
    if (active != null && active.records == 0) {
      return true;
    }
    OtlpBatch next = destination.handOff(active);
    if (next == null) {
      return false;
    }
    active = next;
    return true;
  }

  /// Called by the destination when the flush interval is over: hands off a partly filled batch
  synchronized void handOffPartial() {
    if (!retired && active != null && active.records > 0) {
      handOff();
    }
  }

  /// Records in the active batch, for the queue length metric
  synchronized int records() {
    return active != null ? active.records : 0;
  }

  /// Called by the destination when the thread has terminated or the destination closes: no
  /// more records are accepted.
  ///
  /// @return the last batch, possibly empty, or `null`
  synchronized OtlpBatch retire() {
    retired = true;
    OtlpBatch last = active;
    active = null;
    return last;
  }
}
