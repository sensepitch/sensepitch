package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// Posts batches of request log records via HTTP, independent of the payload, see
/// [BatchPayload]. Used by [OtlpLogDestination].
///
/// Buffering: each thread that logs encodes its records into a batch of its own, see
/// [ThreadBatcher], so logging a request neither allocates nor contends. A full batch is posted
/// right away. When the flush interval is over, the partly filled batches of all threads are
/// taken and queued together, packed into as few requests as possible. A partial batch enters the
/// queue when its thread's batcher takes it, so a full batch the same thread hands over right
/// after it cannot overtake it. The batches waiting to be sent are limited by `maxBufferedBytes`;
/// beyond that, threads drop new records and count them in
/// `sensepitch_request_log_dropped_total`.
///
/// Sending: batches are posted concurrently with [HttpClient#sendAsync], at most
/// `maxConcurrentRequests` at a time; more wait in a queue until a request completes. With one
/// request at a time they arrive in the order they were handed over; with more, batches may
/// arrive out of order, the records within a batch keep theirs. There is no sender thread: the
/// work runs on the threads of the HTTP client, of the shared timer of [CompletableFuture] and of
/// the threads that hand off a full batch. With compression, see [GzipCompressor], a post is
/// compressed and sent on the common pool instead, so an event loop never compresses.
///
/// Failures: a failure that the OTLP specification considers retryable, i.e. a connection error,
/// a timeout or the status codes 429, 502, 503 and 504, opens the [CircuitBreaker] of the
/// endpoint. The batch goes back to the head of the queue, new batches wait as well. After an
/// exponential backoff, honouring `Retry-After`, one batch is posted as a probe; its success
/// sends the queue, its failure doubles the backoff. So the log server gets one request per
/// backoff during an outage. After it, the queue is sent with a slow start: one request in
/// flight, one more with each success, so the concurrency doubles every round trip until it
/// reaches `maxConcurrentRequests`, and a log server that just came back is not flooded by a
/// fleet of proxies. Other failures, e.g. status 400, cannot succeed by retrying: the
/// batch is dropped and counted in `sensepitch_request_log_export_failed_total`, the breaker
/// stays closed. The duration of every request, whatever its outcome, is recorded in
/// `sensepitch_request_log_post_duration_seconds`, so a slow log server shows before it fails.
///
/// The breaker is asked when a batch arrives while nothing waits, and when the sender dispatches
/// after a completion, a flush or the backoff. So its `not_permitted` calls, see
/// [CircuitBreakerMetrics], count refused dispatch attempts, not batches. The batches that wait
/// show in `sensepitch_request_log_buffered_bytes`, those given up in
/// `sensepitch_request_log_dropped_total`.
///
/// Age: a batch whose first record is older than `maximumUnsentLogAge` is dropped. This ends the
/// retries for a persistent error, e.g. a wrong endpoint or a log server that is gone for good,
/// and bounds the time a replaced destination keeps sending, see [#finish()]. The limit of the
/// waiting bytes alone would keep a dead endpoint's batches and its probes forever.
///
/// End: [#finish()] refuses new threads. Each thread ends its batcher itself, see
/// [ThreadBatcher#close()]: an event loop when it terminates, or when a reload asks it to. The
/// sender stops when it is finishing, all batchers are closed and nothing waits anymore, and
/// completes [#stopped()]. Who waits for that, and how long, is up to the caller.
///
/// Concurrency: one private lock guards the queue and the requests in flight. It is taken per
/// batch and per completion, never per record. The bytes and records waiting are atomic counters,
/// the threads reserve a batch with a compare and set, so the limit
/// holds without the lock. A batch is counted as waiting before it leaves its batcher, so the
/// sender cannot stop while a batch is on its way.
///
/// @author Jens Wilke
public class HttpBatchSender<B extends Batch> implements RequestLogMetrics.QueueSource {

  /// Settings of the sender, see [OtlpConfig] for their meaning
  ///
  /// @param maximumUnsentLogAgeMillis age of the first record of a batch after which the batch
  ///   is dropped
  record Settings(
      String name,
      URI endpoint,
      Map<String, String> headers,
      Duration timeout,
      int batchBytes,
      long maxBufferedBytes,
      int maxConcurrentRequests,
      long maximumUnsentLogAgeMillis,
      long flushIntervalMillis,
      boolean gzip,
      Backoff backoff) {}

  /// The batches of one request, with their size, records, and the age of the oldest record
  ///
  /// @param packable a post of one partly filled batch taken by the flush; adjacent ones are
  ///   packed into one request, so several threads share a request
  record Post<B extends Batch>(
      List<B> batches, long size, long records, long firstRecordNanos, boolean packable) {

    /// A post of full batches, or of batches collected for one request
    static <B extends Batch> Post<B> of(List<B> batches) {
      return of(batches, false);
    }

    /// The partly filled batch the flush took from a thread
    static <B extends Batch> Post<B> partialOf(B batch) {
      return of(List.of(batch), true);
    }

    private static <B extends Batch> Post<B> of(List<B> batches, boolean packable) {
      long size = 0;
      long records = 0;
      long first = batches.getFirst().firstRecordNanos;
      for (B batch : batches) {
        size += batch.size();
        records += batch.records;
        if (batch.firstRecordNanos - first < 0) {
          first = batch.firstRecordNanos;
        }
      }
      return new Post<>(batches, size, records, first, packable);
    }

    /// One request with the batches of this post, then those of `next`
    Post<B> append(Post<B> next) {
      List<B> all = new ArrayList<>(batches);
      all.addAll(next.batches);
      return new Post<>(
          all,
          size + next.size,
          records + next.records,
          Math.min(firstRecordNanos, next.firstRecordNanos),
          true);
    }
  }

  /// A target of the posts, with its breaker. One per sender for now; several, e.g. served round
  /// robin, would each get their own.
  record Endpoint(URI uri, CircuitBreaker breaker) {}

  enum Outcome {
    SENT,
    RETRY,
    GIVE_UP
  }

  /// Outcome of one request
  ///
  /// @param reason the failure for the log, with details
  /// @param error the failure as a short code for the metrics, see [CircuitBreaker.Events#error]
  /// @param retryAfterMillis delay the server asked for, `0` if none
  record Result(Outcome outcome, String reason, String error, long retryAfterMillis) {

    static final Result SENT = new Result(Outcome.SENT, null, null, 0);
  }

  /// The `component` label of the breakers of the request log destinations, see
  /// [CircuitBreakerMetrics]; the `name` is the destination
  static final String BREAKER_COMPONENT = "requestLog";

  private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private final Object lock = new Object();

  // the class of the destination, e.g. OtlpLogDestination
  private final ProxyLogger log = ProxyLogger.get(getClass());
  private final String name;
  private final BatchPayload<B> payload;
  private final Endpoint endpoint;

  /// Endpoint, timeout and headers of every request, built once, so an invalid configured header
  /// fails at construction
  private final HttpRequest.Builder requestTemplate;

  private final Duration timeout;
  private final int batchBytes;
  private final long maxBufferedBytes;
  private final int maxConcurrentRequests;
  private final long maxAgeNanos;
  private final long flushIntervalNanos;
  private final RequestLogMetrics metrics;
  private final CounterDataPoint dropped;
  private final CounterDataPoint exportFailed;
  private final CounterDataPoint exportRetries;
  private final CounterDataPoint sentRecords;
  private final CounterDataPoint sentBytes;
  private final DistributionDataPoint postDuration;
  private final HttpClient httpClient;

  /// Compress the bodies, see [GzipCompressor]
  private final boolean gzip;

  /// Compressors for reuse, one per request in flight at most, ended when the sender stops
  private final LinkedBlockingQueue<GzipCompressor> compressors = new LinkedBlockingQueue<>();

  /// Empty batches for reuse, filled when a request completes, taken by the threads
  private final LinkedBlockingQueue<B> free = new LinkedBlockingQueue<>();

  /// Open batchers of the threads, the flush timer takes their partly filled batches. A batcher
  /// removes itself when closed.
  private final List<ThreadBatcher<B>> batchers = new CopyOnWriteArrayList<>();

  /// Size and records of the batches waiting to be sent, from the reservation until the request
  /// succeeded or the batch was dropped
  private final AtomicLong bufferedBytes = new AtomicLong();

  private final AtomicLong bufferedRecords = new AtomicLong();

  private final AtomicBoolean failureReported = new AtomicBoolean();

  /// No new batchers are registered, see [#finish()]. Written under the lock, so a batcher is
  /// either registered before or refused after. Volatile for the read in `stopIfDone()`.
  private volatile boolean finishing;

  private final CompletableFuture<Void> stopped = new CompletableFuture<>();

  // guarded by lock

  /// Posts waiting for a free request slot or for the breaker, oldest first. They are sent by
  /// [#takeDispatchable()] when a request completes (a full slot always has a request in flight,
  /// whose timeout guarantees the completion), when the probe timer fires after the breaker
  /// opened, see [#scheduleProbe(long)], and every flush interval by the flush timer, which also
  /// drops aged posts. A new post waits behind parked ones to keep their order.
  private final ArrayDeque<Post<B>> parked = new ArrayDeque<>();

  private int inFlight;

  /// Requests allowed in flight: `maxConcurrentRequests`, set to one by a retryable failure and
  /// raised by one with each success, the slow start after an outage
  private int window;

  /// Dropping aged batches was logged in this outage
  private boolean ageReported;

  HttpBatchSender(Settings settings, BatchPayload<B> payload, RequestLogMetrics metrics) {
    this.name = settings.name();
    this.payload = payload;
    this.timeout = settings.timeout();
    requestTemplate = requestTemplate(settings, payload.contentType());
    this.batchBytes = settings.batchBytes();
    this.maxBufferedBytes = settings.maxBufferedBytes();
    this.maxConcurrentRequests = settings.maxConcurrentRequests();
    window = maxConcurrentRequests;
    this.maxAgeNanos = TimeUnit.MILLISECONDS.toNanos(settings.maximumUnsentLogAgeMillis());
    this.flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(settings.flushIntervalMillis());
    this.gzip = settings.gzip();
    this.metrics = metrics;
    // cached, so counting does not allocate the label array
    dropped = metrics.dropped.labelValues(name);
    exportFailed = metrics.exportFailed.labelValues(name);
    exportRetries = metrics.exportRetries.labelValues(name);
    sentRecords = metrics.sentRecords.labelValues(name);
    sentBytes = metrics.sentBytes.labelValues(name);
    postDuration = metrics.postDuration.labelValues(name);
    // a probe that reports nothing is replaced once its request timed out
    endpoint =
        new Endpoint(
            settings.endpoint(),
            new CircuitBreaker(
                settings.backoff(),
                timeout.toMillis() + 1_000,
                metrics.circuitBreakers.events(BREAKER_COMPONENT, name)));
    httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    metrics.add(this);
    metrics.circuitBreakers.add(BREAKER_COMPONENT, name, endpoint.breaker());
    scheduleFlush();
  }

  /// The template of all requests
  ///
  /// @throws IllegalArgumentException if a configured header is invalid or restricted, e.g.
  ///   `Content-Length`
  static HttpRequest.Builder requestTemplate(Settings settings, String contentType) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(settings.endpoint())
            .timeout(settings.timeout())
            .header("Content-Type", contentType);
    if (settings.gzip()) {
      builder.header("Content-Encoding", "gzip");
    }
    settings
        .headers()
        .forEach(
            (header, value) -> {
              try {
                builder.header(header, value);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Request log destination "
                        + settings.name()
                        + ": invalid header "
                        + header
                        + ": "
                        + e.getMessage(),
                    e);
              }
            });
    return builder;
  }

  // the threads that log, each with its batcher, see ThreadBatcher

  /// Creates and registers the batcher of the calling thread, see [BatchingRequestLogger]. The
  /// batcher takes its first batch with its first record. A batcher of a finishing sender is
  /// closed right away and never takes one.
  ThreadBatcher<B> newBatcher() {
    ThreadBatcher<B> batcher =
        new ThreadBatcher<>(this, Thread.currentThread(), payload.newEncoder());
    synchronized (lock) {
      if (!finishing) {
        batchers.add(batcher);
        return batcher;
      }
    }
    batcher.close();
    return batcher;
  }

  /// Queues the partly filled batch the flush took from a thread, at once, so a batch the same
  /// thread hands over later cannot overtake it. Adjacent partial batches are packed into one
  /// request up to `batchBytes`. The caller dispatches, see [#flush()].
  void submitPartial(B batch) {
    Post<B> partial = Post.partialOf(batch);
    synchronized (lock) {
      Post<B> tail = parked.peekLast();
      if (tail != null && tail.packable() && tail.size() + partial.size() <= batchBytes) {
        parked.removeLast();
        partial = tail.append(partial);
      }
      parked.addLast(partial);
    }
  }

  /// Posts a filled batch and returns an empty one, from the pool or new.
  ///
  /// @return an empty batch, or `null` if the waiting batches would exceed the limit, then the
  ///   caller keeps the full batch
  B handOff(B full) {
    if (!reserve(full)) {
      return null;
    }
    submit(Post.of(List.of(full)));
    return emptyBatch();
  }

  /// An empty batch, from the pool or new. Allocates only while sending lags behind, the pool
  /// keeps the batches afterwards.
  B emptyBatch() {
    B next = free.poll();
    return next != null ? next : payload.newBatch(batchBytes);
  }

  /// Counts the batch as waiting, if the waiting batches stay within the limit. Adds first and
  /// takes back when over the limit, so two threads near the limit at the same time may both be
  /// refused, which only means a batch waits for the next attempt.
  boolean reserve(B batch) {
    long size = batch.size();
    if (bufferedBytes.addAndGet(size) > maxBufferedBytes) {
      bufferedBytes.addAndGet(-size);
      return false;
    }
    bufferedRecords.addAndGet(batch.records);
    return true;
  }

  /// Counts the last batch of a thread as waiting, regardless of the limit, it is one batch at
  /// most
  void forceReserve(B batch) {
    bufferedBytes.addAndGet(batch.size());
    bufferedRecords.addAndGet(batch.records);
  }

  /// Closes the batchers of the calling thread, its end marker, for a thread that logged without
  /// a thread local of its own to remove, see [BatchingRequestLogger#endThread()]
  void endThread() {
    for (ThreadBatcher<B> batcher : batchers) {
      if (batcher.owner() == Thread.currentThread()) {
        batcher.close();
      }
    }
  }

  /// Called by a batcher when it is closed: sends its last batch, already counted as waiting
  ///
  /// @param last the last batch, `null` if it had no records
  void closed(ThreadBatcher<B> batcher, B last) {
    batchers.remove(batcher);
    if (last != null) {
      submit(Post.of(List.of(last)));
    }
    dispatch();
  }

  // the end

  /// Refuses new batchers and sends what is buffered, without waiting. Records of open batchers
  /// are still accepted and sent; the sender stops when their threads closed them, see
  /// [ThreadBatcher#close()], and all is sent, or dropped after `maximumUnsentLogAge`. Can be
  /// called more than once.
  ///
  /// @return completed when the sender stopped, see [#stopped()]
  public CompletableFuture<Void> finish() {
    synchronized (lock) {
      finishing = true;
    }
    // a last attempt right away, also during a backoff, retries then wait as usual
    endpoint.breaker().skipBackoff();
    dispatch();
    return stopped;
  }

  /// Completed when the sender stopped after [#finish()]: all batchers closed and all batches
  /// sent or dropped
  public CompletableFuture<Void> stopped() {
    return stopped;
  }

  // the flush timer

  /// Runs [#flush()] after the flush interval
  private void scheduleFlush() {
    CompletableFuture.delayedExecutor(flushIntervalNanos, TimeUnit.NANOSECONDS)
        .execute(this::flush);
  }

  /// Takes the partly filled batches of all threads, queues them for packing, and dispatches,
  /// which also drops aged batches. Runs every flush interval until the sender stopped.
  private void flush() {
    try {
      collectPartialBatches();
      dispatch();
    } finally {
      if (!stopped.isDone()) {
        scheduleFlush();
      }
    }
  }

  /// The partly filled batches of the threads, queued and already counted as waiting. A thread
  /// that has terminated without closing its batcher, e.g. not a Netty thread, cannot log anymore,
  /// its batcher is closed here.
  private void collectPartialBatches() {
    for (ThreadBatcher<B> batcher : batchers) {
      if (batcher.owner().isAlive()) {
        batcher.takePartialAndQueue();
      } else {
        batcher.close();
      }
    }
  }

  // sending

  /// Sends the post if a request slot is free and the breaker lets it through, else queues it
  private void submit(Post<B> post) {
    boolean send;
    synchronized (lock) {
      // older posts that wait go first, they wait only while the breaker refuses
      send = parked.isEmpty() && inFlight < window && endpoint.breaker().tryAcquire();
      if (send) {
        inFlight++;
      } else {
        parked.addLast(post);
      }
    }
    if (send) {
      send(post);
    }
  }

  /// Drops aged batches, sends what may be sent, stops if all is done
  private void dispatch() {
    List<Post<B>> next;
    synchronized (lock) {
      discardAged(System.nanoTime());
      next = takeDispatchable();
    }
    for (Post<B> post : next) {
      send(post);
    }
    stopIfDone();
  }

  /// Drops the waiting posts whose oldest record is older than the maximum age
  private void discardAged(long now) {
    long records = 0;
    for (Iterator<Post<B>> it = parked.iterator(); it.hasNext(); ) {
      Post<B> post = it.next();
      if (now - post.firstRecordNanos() > maxAgeNanos) {
        it.remove();
        release(post);
        recycle(post);
        records += post.records();
      }
    }
    if (records > 0) {
      countDropped(records);
      if (!ageReported) {
        ageReported = true;
        log.error(
            "Request log export to "
                + endpoint.uri()
                + " unavailable, dropped "
                + records
                + " records older than maximumUnsentLogAge. Further drops are only counted in"
                + " sensepitch_request_log_dropped_total");
      }
    }
  }

  /// Takes waiting posts while a request slot is free and the breaker lets them through. In the
  /// half open state, that is a single probe.
  private List<Post<B>> takeDispatchable() {
    List<Post<B>> posts = new ArrayList<>();
    while (inFlight < window && !parked.isEmpty() && endpoint.breaker().tryAcquire()) {
      posts.add(parked.pollFirst());
      inFlight++;
    }
    return posts;
  }

  /// Stops a finishing sender once all batchers are closed and nothing waits anymore. A batch
  /// counts as waiting from its reservation until it is sent or dropped, so this covers the
  /// queue, the requests in flight, and a batch on its way from a batcher. Once true, it stays
  /// true: new batchers are refused, and only batchers reserve batches.
  private void stopIfDone() {
    if (finishing && batchers.isEmpty() && bufferedRecords.get() == 0 && stopped.complete(null)) {
      httpClient.shutdownNow();
      // e.g. removed by a configuration reload, the gauges stop reporting it
      metrics.remove(this);
      metrics.circuitBreakers.remove(endpoint.breaker());
      // nothing is in flight anymore, so no compressor is in use
      for (GzipCompressor compressor : compressors) {
        compressor.end();
      }
      compressors.clear();
    }
  }

  /// Posts the batches. The completion runs on a thread of the HTTP client.
  private void send(Post<B> post) {
    if (gzip) {
      // compressing a full batch takes milliseconds, the caller may be an event loop
      CompletableFuture.runAsync(() -> sendCompressed(post), ForkJoinPool.commonPool());
      return;
    }
    List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
    payload.writeBody(
        post.batches(),
        (bytes, offset, length) ->
            parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes, offset, length)));
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    post(post, body, body.contentLength(), null);
  }

  /// Compresses the batches with a pooled compressor and posts them
  private void sendCompressed(Post<B> post) {
    GzipCompressor compressor = compressors.poll();
    if (compressor == null) {
      // the records compress several times, the buffer grows if needed
      compressor = new GzipCompressor(batchBytes / 4);
    }
    compressor.reset();
    payload.writeBody(post.batches(), compressor::write);
    compressor.finish();
    post(
        post,
        HttpRequest.BodyPublishers.ofByteArray(compressor.array(), 0, compressor.length()),
        compressor.length(),
        compressor);
  }

  /// Sends the request and completes the post with its outcome and duration. The duration is
  /// that of the request alone, without the compression before and without the time the post
  /// waited.
  ///
  /// @param wireBytes the length of the body, for the metrics
  /// @param compressor holds the body, returned to the pool when the request completed, `null`
  ///   without compression
  private void post(
      Post<B> post, HttpRequest.BodyPublisher body, long wireBytes, GzipCompressor compressor) {
    HttpRequest request = requestTemplate.copy().POST(body).build();
    long start = System.nanoTime();
    httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (compressor != null) {
                compressors.add(compressor);
              }
              complete(
                  post,
                  wireBytes,
                  System.nanoTime() - start,
                  failure != null
                      ? failure(failure)
                      : result(response.statusCode(), Backoff.retryAfterMillis(response)));
            });
  }

  /// Outcome of a response: the OTLP specification considers 429, 502, 503 and 504 retryable,
  /// other failures cannot succeed by retrying
  ///
  /// @param retryAfterMillis delay the server requested, see [Backoff#retryAfterMillis]
  static Result result(int status, long retryAfterMillis) {
    if (status >= 200 && status < 300) {
      return Result.SENT;
    }
    String reason = "HTTP status " + status;
    String error = "http_" + status;
    if (status == 429 || status == 502 || status == 503 || status == 504) {
      return new Result(Outcome.RETRY, reason, error, retryAfterMillis);
    }
    return new Result(Outcome.GIVE_UP, reason, error, 0);
  }

  /// Outcome of a request without response: a connection error or timeout is retryable, anything
  /// else is unexpected and gives up the batch
  static Result failure(Throwable failure) {
    Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
    if (cause instanceof IOException) {
      return new Result(Outcome.RETRY, cause.toString(), error((IOException) cause), 0);
    }
    return new Result(Outcome.GIVE_UP, "unexpected " + cause, "unexpected", 0);
  }

  /// The short code of a connection error or timeout, a connect timeout counts as `connect`
  static String error(IOException cause) {
    if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
      return "connect";
    }
    if (cause instanceof HttpTimeoutException) {
      return "timeout";
    }
    return "io";
  }

  /// Accounts for the outcome of a post: frees its request slot, releases its batches or parks
  /// it for a retry, and updates the breaker and the window. Then sends what is next.
  ///
  /// @param wireBytes length of the request body, compressed if enabled
  /// @param durationNanos time the request took, for the latency metric
  private void complete(Post<B> post, long wireBytes, long durationNanos, Result result) {
    postDuration.observe(durationNanos / NANOS_PER_SECOND);
    synchronized (lock) {
      CircuitBreaker.State before = null;
      inFlight--;
      CircuitBreaker breaker = endpoint.breaker();
      switch (result.outcome()) {
        case SENT -> {
          release(post);
          recycle(post);
          window = Math.min(maxConcurrentRequests, window + 1);
          sentRecords.inc(post.records());
          sentBytes.inc(wireBytes);
          int failures = breaker.failures();
          answered(breaker.onSuccess(), failures);
        }
        case GIVE_UP -> {
          release(post);
          recycle(post);
          exportFailed.inc();
          countDropped(post.records());
          int failures = breaker.failures();
          // closes an open breaker as well, the server answered
          answered(breaker.onIgnored(result.error()), failures);
        }
        default -> {
          exportRetries.inc();
          parked.addFirst(post);
          window = 1;
          before = breaker.onFailure(result.retryAfterMillis(), result.error());
          if (before != CircuitBreaker.State.OPEN) {
            scheduleProbe(breaker.retryDelayNanos());
          }
        }
      }
      report(result, before);
    }
    dispatch();
  }

  /// Ends an outage when the server answered, with a success or a status a retry cannot fix:
  /// logs the recovery and lets the next aged batches be logged again
  ///
  /// @param before the state of the breaker before the answer
  /// @param failures failed attempts of the outage
  private void answered(CircuitBreaker.State before, int failures) {
    if (before != CircuitBreaker.State.CLOSED) {
      ageReported = false;
      log.info(
          "Request log export to "
              + endpoint.uri()
              + " recovered after "
              + failures
              + " failed attempts, sending the buffered records");
    }
  }

  /// Logs the transitions of failures only: the first failure of an outage and the first dropped
  /// batch; everything else is counted in the metrics. The recovery is logged by
  /// [#answered(CircuitBreaker.State, int)].
  ///
  /// @param before the state of the breaker before a retryable failure, `null` otherwise
  private void report(Result result, CircuitBreaker.State before) {
    switch (result.outcome()) {
      case SENT -> {}
      case GIVE_UP -> {
        if (failureReported.compareAndSet(false, true)) {
          log.error(
              "Request log export to "
                  + endpoint.uri()
                  + " failed, dropped a batch: "
                  + result.reason()
                  + ". Further failures are only counted in"
                  + " sensepitch_request_log_export_failed_total");
        }
      }
      default -> {
        if (before == CircuitBreaker.State.CLOSED) {
          log.warn(
              "Request log export to "
                  + endpoint.uri()
                  + " failed: "
                  + result.reason()
                  + ". Buffering records and retrying, waiting batches are limited by"
                  + " maxBufferedBytes and maximumUnsentLogAge");
        }
      }
    }
  }

  /// Dispatches again when the backoff is over, so the parked posts do not wait for the next
  /// flush or completion
  private void scheduleProbe(long delayNanos) {
    // when the backoff is over, the breaker lets a probe through
    CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(this::dispatch);
  }

  /// The batches of the post are no longer waiting: sent or dropped
  private void release(Post<B> post) {
    bufferedBytes.addAndGet(-post.size());
    bufferedRecords.addAndGet(-post.records());
  }

  /// Keeps the batches of a completed post for reuse. One batch per thread and one spare suffice
  /// when sending keeps up, more are released, so the memory taken during an outage is returned.
  private void recycle(Post<B> post) {
    for (B batch : post.batches()) {
      batch.reset();
      if (free.size() <= batchers.size()) {
        free.add(batch);
      }
    }
  }

  // metrics and accessors

  @Override
  public String destination() {
    return name;
  }

  /// Records waiting in the batches of the threads and in the queue
  @Override
  public int queueSize() {
    long records = bufferedRecords.get();
    for (ThreadBatcher<B> batcher : batchers) {
      records += batcher.records();
    }
    return (int) Math.min(records, Integer.MAX_VALUE);
  }

  /// Bytes of the batches waiting to be sent
  @Override
  public long bufferedBytes() {
    return bufferedBytes.get();
  }

  /// Requests allowed in flight, see the slow start, for tests
  int window() {
    synchronized (lock) {
      return window;
    }
  }

  /// Empty batches kept for reuse, for tests
  int pooledBatches() {
    return free.size();
  }

  public RequestLogMetrics metrics() {
    return metrics;
  }

  int batchBytes() {
    return batchBytes;
  }

  void countDropped(long records) {
    dropped.inc(records);
  }

  /// Number of records of this destination dropped since start
  public long droppedCount() {
    return (long) dropped.get();
  }
}
