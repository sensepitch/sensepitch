package org.sensepitch.edge;

import io.netty.util.concurrent.FastThreadLocal;
import java.util.concurrent.CompletableFuture;

/// Logs requests into batches that an [HttpBatchSender] posts. Each thread gets a batcher of its
/// own, see [ThreadBatcher], which encodes the record into a buffer of that thread, so logging a
/// request does not allocate.
///
/// The event loops are Netty threads, for them the thread local lookup is an array access. When
/// an event loop terminates, Netty removes its thread locals after the loop ran its last tasks,
/// which closes its batcher: the end marker of that thread, see [ThreadBatcher#close()].
///
/// @author Jens Wilke
public class BatchingRequestLogger<B extends Batch> implements RequestLogger {

  private final HttpBatchSender<B> sender;

  private final FastThreadLocal<ThreadBatcher<B>> batchers =
      new FastThreadLocal<>() {
        @Override
        protected ThreadBatcher<B> initialValue() {
          return sender.newBatcher();
        }

        @Override
        protected void onRemoval(ThreadBatcher<B> batcher) {
          batcher.close();
        }
      };

  public BatchingRequestLogger(HttpBatchSender<B> sender) {
    this.sender = sender;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    // refused once the thread ended its batcher, or got one from a finishing sender
    if (!batchers.get().log(info)) {
      sender.countDropped(1);
    }
  }

  /// Writes the end marker of the calling thread: closes its batcher, if it has one. A reload
  /// runs this on each event loop after it replaced the destinations, so it comes after the
  /// last record the loop logs into this destination.
  void endThread() {
    batchers.remove();
  }

  /// Refuses new threads and completes when the threads that log closed their batchers and all
  /// is sent, see [HttpBatchSender#finish()]. Does not wait.
  CompletableFuture<Void> finish() {
    return sender.finish();
  }

  HttpBatchSender<B> sender() {
    return sender;
  }

  /// Ends the batcher of the calling thread and finishes the sender, without waiting, see
  /// [#finish()]
  @Override
  public void close() {
    endThread();
    sender.finish();
  }
}
