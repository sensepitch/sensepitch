# Request log: logging and batching

Design notes for the request log: how a completed request becomes a record, and how the records
reach standard out or an OTLP log server. The main classes are
[`ReloadableRequestLogger`](../../src/main/java/org/sensepitch/edge/ReloadableRequestLogger.java),
[`BatchingRequestLogger`](../../src/main/java/org/sensepitch/edge/BatchingRequestLogger.java),
[`ThreadBatcher`](../../src/main/java/org/sensepitch/edge/ThreadBatcher.java),
[`HttpBatchSender`](../../src/main/java/org/sensepitch/edge/HttpBatchSender.java) and
[`AsyncRequestLogWriter`](../../src/main/java/org/sensepitch/edge/AsyncRequestLogWriter.java).
The class docs describe the behaviour; this document records why it is built this way, what was
considered instead, and what is still missing. The circuit breaker that guards the export has its
own document, [circuit-breaker.md](circuit-breaker.md).

## Purpose and scope

Every request the proxy handles produces one record: request, response status, sizes, timings,
upstream, trace context, outcome. The records go to one or more destinations, configured under
`requestLog.destinations`:

- `stdout`: JSON lines or plain text on standard out, for a container log collector.
- `otlp`: OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an OpenTelemetry collector.

The requirements, in order:

1. **Never stall traffic.** Logging runs on the event loops. A slow or dead log server, or a full
   pipe on standard out, must not slow down a request. Losing records is preferable to that.
2. **Cheap per request.** At tens of thousands of requests per second, logging must not allocate
   or contend per record on the OTLP path.
3. **Lose nothing in normal operation.** A restart of the log server, a configuration reload, or a
   graceful shutdown of the proxy must not lose records.
4. **Observable.** What was sent, dropped, retried or is waiting shows in metrics.

Diagnostic logging (Log4j2 to standard error) is out of scope, see `LogTarget`.

## Overview

```
event loop                     HttpBatchSender (per OTLP destination)
-----------                    ------------------------------------------------------------
RequestLoggingHandler.log()
 └ ReloadableRequestLogger      reads the destination array once
    ├ stdout: encode line ───► AsyncRequestLogWriter queue ─► writer thread ─► standard out
    └ otlp:   BatchingRequestLogger
               └ ThreadBatcher of this thread
                  encode record into own batch (in place, no allocation)
                  batch full ──► handOff ─► reserve bytes ─► submit ─► sendAsync ─► log server
                                            │                 └ parked queue if no slot or breaker
                                            └ gzip: compression thread ─► sendAsync
               flush timer ───► takePartialAndQueue of all threads, packed into posts
               thread ends ───► close: last batch + end marker ─► sender stops when all closed
```

## Design decisions

### One batch per thread, encoded in place

Each thread that logs, in practice each event loop, owns a [`ThreadBatcher`] with an `active`
batch, a byte buffer of `otlp.batchBytes` (default 1 MiB). A record is encoded directly into that
buffer as a framed `ScopeLogs.log_records` entry by `OtlpRequestLogSink`. Nothing is allocated per
record, and nothing is shared with other threads.

- The batcher is found through a Netty `FastThreadLocal`, an array access on an event loop.
- The batcher's private lock guards `active` and `closed`. The only other thread that takes it is
  the flush timer, once per flush interval, so the lock is uncontended.
- A full batch is handed to the sender, which returns an empty one from a pool. The pool keeps one
  batch per thread plus one spare, so allocation stops once sending keeps up, and memory taken
  during an outage is given back afterwards.

A batch is the unit of sending: the encoded records are passed to the HTTP client as they are.
Only the enclosing `ExportLogsServiceRequest`, `ResourceLogs` and `ScopeLogs` are encoded per
request (`OtlpBatchPayload`).

### No sender thread

`HttpBatchSender` has no thread. Work runs on whatever thread causes it:

- the event loop that hands off a full batch submits it, and sends it if a request slot is free;
- the HTTP client's completion thread accounts for the result and dispatches the next posts;
- the shared timer of `CompletableFuture` runs the flush and the probe after a backoff;
- with gzip, one dedicated compression thread compresses and then sends, see below.

One private lock in the sender guards the queue of waiting posts and the requests in flight. It
is taken per batch and per completion, never per record. The waiting bytes and records are atomic
counters, so a thread reserves room for a batch without the lock.

### Private locks preserve ownership and lock ordering

The sender and each batcher have their own private final lock object, so callers cannot hold an
instance monitor and stall logging. This costs one object per instance; no performance gain is
claimed. Critical sections keep the same boundaries as the former instance monitors.

A batcher may enter the sender while holding its lock when handing off a full batch or queueing a
partial one. The sender never takes a batcher lock, so it cannot take the locks in reverse order.
A closing batcher calls `sender.closed` after releasing its lock; HTTP sends and the sender's
completion dispatch also stay outside the sender lock.
`finishing` stays volatile because `stopIfDone` reads it without the lock. The other mutable sender
state in critical sections and the batcher's `active` and `closed` need no volatile reads.

The proxy serializes reloads and shutdown registration with its own private lock, then waits for
shutdown outside it. `ConfigFileWatcher` keeps file checks and reload callbacks under its private
lock, including the reentrant initial check from `start`. The metrics registration lock
serializes replacement and removal of breakers; gauge callbacks still read copy-on-write
snapshots without it and use the breaker's own accessors for state. These changes preserve the
existing callback boundaries and nesting.

### Bounded memory, drop the newest

The batches waiting to be sent are limited by `otlp.maxBufferedBytes`, at least
`2 × event loops × batchBytes`, so every loop can have one batch in flight and one filling. A batch
is reserved, i.e. counted as waiting, before it leaves its batcher. If the reservation fails, the
thread keeps its full batch and drops new records until there is room again, counted in
`sensepitch_request_log_dropped_total`.

So the newest records are dropped and the waiting ones are kept: during an outage, the records
from its start survive, and the gap is a contiguous stretch at the end, which is easier to reason
about than holes spread over the outage.

### Flush interval for partly filled batches

A quiet proxy may take minutes to fill a batch. Every `otlp.flushIntervalMillis` (default 1 s)
the flush timer takes the partly filled batch of each thread (`takePartialAndQueue`), if there is
room to reserve it, and packs them into posts of up to `batchBytes`. So a flush sends one request
per `batchBytes`, not one per event loop. The thread takes a new batch with its next record.

The batch enters the sender queue in the same critical section that takes it from the batcher. If
it were queued only after all partials were collected, the logging thread could fill and hand over
the next full batch in between, ahead of the partial. That happened on 2026-11-20 in
`OtlpLogDestinationIT.outageLosesNothing`: records 898-981 arrived before 850-897 although
`maxConcurrentRequests` was 1. Queuing under the batcher lock keeps the thread's order; adjacent
partial posts still pack into one request.

The flush also drops waiting posts whose first record is older than
`otlp.maximumUnsentLogAgeMillis` (default 5 min) and closes the batchers of threads that died
without closing them (non-Netty threads, e.g. in tests).

### Concurrent requests, slow start, circuit breaker

Posts are sent with `HttpClient.sendAsync`, at most `otlp.maxConcurrentRequests` at a time
(default: the number of event loops). More wait in a queue, oldest first. With one request at a
time the batches arrive in that queue order; with more, they may arrive out of order, while the
records within a batch keep theirs. A log server orders by timestamp, so this was accepted for
throughput.

A failure the OTLP specification considers retryable (connection error, timeout, 429, 502, 503,
504) puts the post back at the head of the queue and opens the circuit breaker; one probe per
backoff tests the server. After recovery the concurrency restarts at one request and grows by one
per success, so a fleet of proxies does not flood a log server that just came back. Other failures
(e.g. 400) cannot succeed by retrying: the batch is dropped and counted in
`sensepitch_request_log_export_failed_total`. Details in [circuit-breaker.md](circuit-breaker.md).

### End markers instead of timed closing

Finishing a destination (on reload or shutdown) must not lose the last records of any thread, and
must not wait longer than needed. The sender cannot know which threads will still log, so each
thread signals its own end:

- `ThreadBatcher.close()` is the end marker: it refuses further records, reserves the last batch
  and hands it to the sender.
- An event loop writes it when it terminates: Netty removes its `FastThreadLocal`s after the loop
  ran its last tasks, and `onRemoval` closes the batcher. That comes after the loop closed its
  channels and logged their aborted requests, so the order is right by construction.
- On reload, `BatchingRequestLogger.endThread()` runs as a task on each event loop after the
  destination array was switched, so it runs after the last `logRequest` of that loop into the old
  destination.

`HttpBatchSender.finish()` says "no new threads will come": it refuses new batchers and gives the
breaker a last attempt. The sender stops, and completes `stopped()`, once it is finishing, all
batchers are closed, and nothing is waiting or in flight. Both are needed: an empty list of
batchers alone also holds for a fresh destination before its first record. The caller calls
`finish()` only once it knows all end markers are written or queued: after the per-loop marker
tasks on reload, after the loops terminated at shutdown. A loop that logs its first record into a
destination while it is being finished still gets a batcher.

Who waits for `stopped()`, and how long, is up to the caller. The sender has no timeouts and no
give-up path of its own; a finishing sender during an outage still ends by the age limit.

### Reload keeps unchanged destinations

`ReloadableRequestLogger` holds the destinations in an array that is replaced as a whole; logging
a request reads it once. A reload builds the new destinations first (`prepare`), so a rejected
configuration leaves the active ones untouched, then switches (`apply`).

- A destination with the same name and an equal configuration is kept, with its buffered records,
  its connections and its breaker.
- A changed or removed OTLP destination is retired: end markers on every loop, then `finish()`.
  It keeps sending its backlog to its own endpoint in the background, while the new one starts.
  `finish()` at shutdown includes the destinations still draining.
- The name, default the type, identifies a destination across reloads; a positional default would
  shift when a destination is inserted. It is also the `destination` metric label.

### Graceful shutdown drains the request log last

The shutdown in `Proxy` follows the data: keep accepting for `shutdownAcceptGraceMillis` while
readiness is already false, stop the listener, let the requests in flight complete (up to
`shutdownTimeoutMillis − requestLog.drainTimeoutMillis` from the start), shut down the event
loops, which closes the remaining connections, logs their requests as aborted (499) and writes
the end markers, then wait for the destinations to send everything until `shutdownTimeoutMillis`.
The request log gets its own budget, by default a third of the total, because the records of the
aborted requests only exist after the connections are gone. What is left at a deadline is logged
at `INFO`: the requests in flight per upstream, the unsent records per destination, and whether
the event loop group is still running. A reload and the start of the shutdown run one at a time.

The accept grace is built in rather than left to a `preStop` hook with `sleep`, which needs a
shell and a `sleep` binary in the image. It is part of the one deadline and cannot eat the
request log's reservation, so the platform grace period only has to cover
`shutdownTimeoutMillis`. It is a top level key next to `shutdownTimeoutMillis`, not under
`admin`: a present `admin` section starts the admin server, and the grace is needed with an
external load balancer as well. Traefik has the same option for its entry points,
`lifeCycle.requestAcceptGraceTimeout` (from its documentation, not re-checked for this text); the
name avoids "drain", which here already means the request log (`drainTimeoutMillis`) and the
closing of connections.

### Gzip on one dedicated compression thread

OTLP posts are gzip compressed by default (`otlp.compression: gzip | none`). Records repeat hosts,
attribute keys and user agents, so a post shrinks several times; that saves egress to a remote log
server.

- Each destination has one daemon compression thread and a queue of `maxConcurrentRequests`
  entries. Every in-flight post schedules at most one compression task, so the queue cannot
  overflow and there is no caller-runs fallback: an event loop never compresses. The common pool
  was rejected because it is shared with the rest of the JVM and its parallelism is unrelated to
  request logging.
- The executor is shut down when the sender stops. That happens only after the last request
  completed, so no compression task is queued or running then. The thread is a daemon, so a sender
  that is never finished cannot keep the JVM alive.
- `GzipCompressor` keeps a `Deflater` at `otlp.compressionLevel`, a CRC and a growable buffer and
  writes the gzip header and trailer by hand; it is pooled, one per request in flight, so steady
  state does not allocate. `GZIPOutputStream` allocates its deflater and buffers per instance.
- The level ranges from `1` (fastest, the default and the previous fixed `BEST_SPEED`) to `9`
  (smallest). A configured level replaces the destination on reload, like any other changed
  setting.
- The payload writes its parts to a sink (`BatchPayload.writeBody`), so the same parts are either
  concatenated without copying or streamed through the compressor.
- All size limits stay uncompressed; only `sent_bytes_total` counts what went over the wire.

Measured on 2026-11-20 on an AMD EPYC-Milan, 2 vCPU, Linux 7.0.0, OpenJDK 25.0.5, with the
process pinned to one CPU (`taskset -c 0`). The payload was 1,048,765 bytes of synthetic repetitive
request-log JSON lines, compressed by one reused `GzipCompressor`; after 20 warm-up iterations,
the best of three 100-iteration runs was taken, using the current thread's CPU time. It is an
indicative comparison, not a production trace.

| Level | Compressed bytes | Ratio | CPU ms per MiB | CPU MiB/s |
| --- | --- | --- | --- | --- |
| 1 | 77,141 | 13.6 | 2.4 | 409 |
| 3 | 72,304 | 14.5 | 2.5 | 396 |
| 6 | 61,725 | 17.0 | 6.0 | 167 |
| 9 | 54,441 | 19.3 | 16.6 | 60 |

Level 3 saved 6% more bytes than level 1 at about the same CPU cost on this payload. Level 9 saved
29% against level 1 but cost about seven times the CPU. One thread therefore keeps up with roughly
400 MiB/s at the default level and about 60 MiB/s at level 9, per destination.

### Standard out: a queue and a writer thread

The stdout path predates the batching and is deliberately different. A JSON line is rendered on the
event loop and put into a bounded queue (8192 records); a dedicated thread writes everything queued
into its own buffer and flushes when the queue is empty. Under load this gives few large writes,
when idle each record appears at once, without a timer. A full queue drops the record. The reason
for the thread: with a shared buffered stream, the event loop that fills the buffer does the
`write` system call under the stream lock, and a slow pipe would stall every loop.

### Disable by flag

`requestLog.disabled` and `requestLog.destinations[].disabled` default to `false`, so a later
configuration layer can switch off a default, e.g. the stdout destination while OTLP stays. A list
whose destinations are all disabled means no request log; an empty list is rejected as a likely
mistake. Names are validated over all configured destinations, disabled ones included, so enabling
one later does not change the others' metric labels.

### Metrics

Per destination, label `destination`:

| Metric | Meaning |
|---|---|
| `sensepitch_request_log_dropped_total` | records dropped: no room, aged out, batch given up, or logged after the end marker |
| `sensepitch_request_log_export_failed_total` | batches given up, retrying cannot succeed |
| `sensepitch_request_log_export_retries_total` | failed attempts that are retried |
| `sensepitch_request_log_sent_records_total`, `_sent_bytes_total` | sent successfully; bytes as on the wire |
| `sensepitch_request_log_post_duration_seconds` | histogram of the request duration of the posts, any outcome (OTLP) |
| `sensepitch_request_log_buffered_bytes` | bytes waiting to be sent (OTLP) |
| `sensepitch_request_log_queued` | records waiting (stdout queue, OTLP batches) |

Plus the breaker of each OTLP destination, `sensepitch_circuitbreaker_*{component="requestLog",
name=<destination>}`. Records lost at the shutdown deadline are only logged: the process exits
right after, so a counter would never be scraped.

## Alternatives considered

### OpenTelemetry Java SDK with `BatchLogRecordProcessor`

The standard way to export OTLP logs from Java. Not used:

- It builds a `LogRecordData` object per record, with attribute maps, and puts it into a shared
  queue; the export thread encodes it again. That is several allocations and a contended queue per
  request, against requirement 2.
- It brings the SDK, the exporter and their dependencies into the shaded jar for one feature.
- Its exporter has no age limit and no breaker with `Retry-After`; its queue drops on overflow
  without the "keep the oldest" policy.

Hand-written protobuf encoding of one message type was judged cheaper than the dependency. The
encoding is checked against the official protobuf classes in the tests.

### A shared record queue with an encoding thread

The shape of the stdout writer: event loops put records into one queue, a thread encodes and
writes them. Not used for OTLP, because the queue is contended per record and each record must be
copied or allocated to get into it. Per-thread batches avoid both; the only shared step is the
hand-off of a whole batch.

### One sender thread posting in order

The first OTLP implementation (`OtlpThreadLogger` with `OtlpLogDestination`, removed in the
refactoring to `HttpBatchSender`) already had per-thread batches, but one sender thread per
destination posted the waiting batches one after the other, in order. Replaced because one
request per round trip limits the throughput, and a thread per destination needs its own
lifecycle on reload and shutdown. `sendAsync` with a concurrency cap and a slow start removes the
thread; the price is the order across batches, see the limitations.

### Logging through Log4j2 with an appender

Log4j2 is already used for diagnostics. Not used for the request log: a Log4j2 event is an object
per record with a formatted message, and the asynchronous appenders need a ring buffer and a
thread. The request log has a fixed schema, so encoding it directly is simpler and cheaper, and it
keeps standard out free of diagnostics.

### Drop the oldest records when full

Would keep the most recent picture during a long outage. Rejected: dropping from the head means
unreserving batches that may already be in flight or parked, which needs the lock per drop and
breaks the "reserved before it leaves" invariant the stop condition relies on. Dropping the newest
is a refused reservation, nothing more.

### Persisting waiting batches to disk

Would survive a restart of the proxy during an outage. Not done yet: it needs a disk budget,
fsync policy, replay order and cleanup, and a container usually has no persistent volume for the
proxy. Kept as a possible improvement.

### Timed close with a grace period

The earlier `close()` waited for a fixed grace period after a reload for loops to stop logging into
the old destination, then retired the batchers of all threads from outside, with its own timeout,
give-up path and a `STOPPED` state that every method had to check. Replaced by end markers: the
hand-over is exact rather than timed, each thread closes its own batcher, and the sender's
lifecycle is three facts: finishing, open batchers, buffered records.

### Counting end markers against the known event loops

Instead of `finish()`, the sender could know the event loop group and wait for one marker per
loop, including loops that never logged into it. Rejected: it ties the generic sender to Netty's
group, and loops that never logged would have to be told to send an empty marker. The caller
already knows when all markers are written; `finish()` is how it says so.

### Naming the event loops that did not end

The shutdown report could list the loops still running instead of only saying that the group is.
Not done: a loop that does not end is a bug of ours, and its thread name does not tell which
handler is stuck; a thread dump taken during the shutdown does. The JVM exits right after the
report, so the name would not help to find the loop either.

### Compress on the calling thread, the common pool, or with `GZIPOutputStream`

Compressing inline would block an event loop for milliseconds per full batch. A
`GZIPOutputStream` per post allocates a deflater with native memory and two buffers per request.
The common pool avoids the event loop but shares its parallelism with the rest of the JVM and has
no queue limit that matches the sender's requests in flight. A dedicated bounded executor gives
compression a predictable CPU budget and a shutdown point of its own.
zstd would compress better and faster, but it is not supported by all OTLP receivers and would
need a native library; gzip is accepted by VictoriaLogs and the OpenTelemetry collector.

### Batching the stdout path the same way

Would make stdout allocation free as well. Not done: the stdout writer is simple, has its own
backpressure, and a container's log pipe is rarely the bottleneck. See the improvements.

## Limitations

Known limits of the current implementation, checked against the code on 2026-11-14:

- **Memory only.** Waiting batches are lost when the proxy itself restarts during an outage of the
  log server, or when the shutdown deadline is hit; the latter is only logged, not counted.
- **Order across batches.** With `maxConcurrentRequests > 1`, batches may arrive out of order. A
  log server that does not order by timestamp shows them interleaved. With one request at a time,
  the queue order is the order the threads took and handed over the batches.
- **Whole batches age out.** The age limit drops a waiting post by its oldest record, so younger
  records in the same post go with it.
- **Partly filled batches need room.** When the waiting bytes are at the limit, the flush skips
  partly filled batches; their records wait in the thread's batch, which then drops new records
  once it is full.
- **The stdout path allocates.** A rendered `String` and its bytes per record, and a bounded queue
  of 8192 records shared by all loops. Its writer thread is started even when stdout is disabled,
  so a reload can enable it again.
- **Compression is one thread.** A destination compresses one post at a time; a high level or very
  high log volume can make compression the bottleneck, so posts wait and their thread batches can
  fill. gzip is the only codec, zstd is not implemented.
- **One endpoint per destination.** No failover to a second log server; a second destination
  sends a copy, not a fallback.
- **Reload of a changed destination.** Old and new sender run side by side until the old one has
  drained; their metrics share the `destination` label, and the breaker gauges show the new one
  only.
- **Fixed limits.** The backoff bounds (1 s to 30 s) and the stdout queue size are constants.

## Planned improvements

In rough order of priority. Items 1, 2, 4, 5 and 7 are tracked in [TODO.md](../../TODO.md),
section "Request log", item 3 (the backoff part) under "Circuit breaker"; item 6 is a proposal.

1. **Shutdown report wording.** Done: report elapsed phase times and a still-running event loop
   group; naming the loops was dropped, see "Alternatives considered".
2. **Post latency.** Done: `sensepitch_request_log_post_duration_seconds`, a histogram of the
   post duration per destination, to see a slow log server before it fails.
3. **Configurable backoff and stdout queue size,** with the current values as defaults. The
   backoff is tracked in TODO.md under "Circuit breaker".
4. **Compression executor and level.** Done: one bounded executor per destination and
   `otlp.compressionLevel`. zstd remains open until the target log servers support it.
5. **Failover endpoints.** Several endpoints per destination, each with its own breaker, see the
   `Endpoint` record in `HttpBatchSender`.
6. **Allocation free stdout.** Encode JSON lines into per-thread buffers as the OTLP path does, and
   write them from the writer thread, removing the per-record `String`.
7. **Disk spool.** Optionally persist waiting batches when the memory limit is reached or at the
   shutdown deadline, replayed on start. Needs a volume and a budget; only if deployments ask for
   it.
