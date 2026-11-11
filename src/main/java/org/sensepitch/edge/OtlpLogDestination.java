package org.sensepitch.edge;

import static org.sensepitch.edge.OtlpEncoding.EXPORT_RESOURCE_LOGS;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_ATTRIBUTES;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_RESOURCE;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_SCHEMA_URL;
import static org.sensepitch.edge.OtlpEncoding.RESOURCE_LOGS_SCOPE_LOGS;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_SCHEMA_URL;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_LOGS_SCOPE;
import static org.sensepitch.edge.OtlpEncoding.SCOPE_NAME;
import static org.sensepitch.edge.OtlpEncoding.stringAttribute;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/// Sends request log records via OTLP/HTTP with protobuf encoding, e.g. to VictoriaLogs or an
/// OpenTelemetry collector.
///
/// Buffering: each thread that logs encodes its records into a batch of its own, see
/// [OtlpThreadLogger], so logging a request neither allocates nor contends on a shared queue. A
/// full batch is handed to this destination, which queues it and gives the thread an empty
/// batch from its pool. When the flush interval is over, the destination also takes the partly
/// filled batches.
/// The pool grows while sending lags behind, e.g. during an outage of the log server, and shrinks
/// again afterwards. The batches waiting to be sent are limited by `maxBufferedBytes`; beyond
/// that, threads drop new records and count them in `sensepitch_request_log_dropped_total`.
///
/// Sending: one sender thread posts the waiting batches in order, each as one
/// `ExportLogsServiceRequest`. The records in a batch are already encoded `log_records` entries,
/// only the enclosing messages are written per request: the configured resource attributes and the
/// instrumentation scope {@value #SCOPE}. Failures that the OTLP specification considers
/// retryable, i.e. connection errors, timeouts and the status codes 429, 502, 503 and 504, are
/// retried with exponential backoff, honouring `Retry-After`. The batch stays at the head of the
/// queue meanwhile, so a restart of the log server loses no records. Other failures, e.g. status
/// 400, cannot succeed by retrying: the batch is dropped and counted in
/// `sensepitch_request_log_export_failed_total`.
///
/// On [#close()] the waiting batches are sent within a few seconds, what is left is counted as
/// dropped. Batches are kept in memory only, so they are lost when the proxy itself is restarted
/// during an outage of the log server.
///
/// @see <a href="https://opentelemetry.io/docs/specs/otlp/#otlphttp">OTLP/HTTP</a>
/// @author Jens Wilke
public class OtlpLogDestination implements RequestLogMetrics.QueueSource, AutoCloseable {

  /// Default of the `service.name` resource attribute
  public static final String DEFAULT_SERVICE_NAME = "sensepitch-edge";

  /// Name of the instrumentation scope of the records
  public static final String SCOPE = "org.sensepitch.edge";

  static final String CONTENT_TYPE = "application/x-protobuf";

  static final long INITIAL_RETRY_DELAY_MILLIS = 1_000;
  static final long MAX_RETRY_DELAY_MILLIS = 30_000;

  /// Upper bound of a delay requested via `Retry-After`
  static final long MAX_RETRY_AFTER_MILLIS = 300_000;

  /// Time [#close()] spends sending the waiting batches
  static final long CLOSE_TIMEOUT_MILLIS = 5_000;

  private final ProxyLogger log = ProxyLogger.get(OtlpLogDestination.class);
  private final String name;
  private final URI endpoint;
  private final Map<String, String> headers;
  private final Duration timeout;
  private final int batchBytes;
  private final long maxBufferedBytes;
  private final long flushIntervalNanos;
  private final long initialRetryDelayMillis;
  private final long maxRetryDelayMillis;
  private final long closeTimeoutMillis;
  private final RequestLogMetrics metrics;
  private final CounterDataPoint dropped;
  private final CounterDataPoint exportFailed;
  private final CounterDataPoint exportRetries;

  /// `ResourceLogs.resource` field, constant
  private final byte[] resourceField;

  /// `ScopeLogs.scope` field, constant
  private final byte[] scopeField;

  /// `ScopeLogs.schema_url` field, follows the records
  private final byte[] scopeSchemaField;

  /// `ResourceLogs.schema_url` field, follows the `ScopeLogs`
  private final byte[] resourceSchemaField;

  private final HttpClient client;
  private final List<OtlpThreadLogger> threadLoggers = new CopyOnWriteArrayList<>();
  private final Thread sender;
  private volatile boolean closed;

  /// Takes over the buffered records when a reload replaced this destination, see [#handOverTo]
  private volatile OtlpLogDestination successor;

  /// Handovers of replaced destinations into this one that have not arrived yet, closing waits
  /// for them, see [#takeOver(List)]
  private final AtomicInteger incoming = new AtomicInteger();
  private final AtomicBoolean failureReported = new AtomicBoolean();

  // guarded by this, ArrayDeque does not allocate per element
  private final ArrayDeque<OtlpBatch> pending = new ArrayDeque<>();
  private final ArrayDeque<OtlpBatch> free = new ArrayDeque<>();
  private long pendingBytes;
  private long pendingRecords;

  /// The sender has sent or handed over its batches and ended, see [#takeOver(List)]
  private boolean finished;

  /// Failed attempts in a row, sender thread only
  private int retries;

  public OtlpLogDestination(String name, OtlpConfig config) {
    this(name, config, new RequestLogMetrics());
  }

  public OtlpLogDestination(String name, OtlpConfig config, RequestLogMetrics metrics) {
    this(
        name,
        config,
        metrics,
        INITIAL_RETRY_DELAY_MILLIS,
        MAX_RETRY_DELAY_MILLIS,
        CLOSE_TIMEOUT_MILLIS);
  }

  /// With other delays, for tests
  OtlpLogDestination(
      String name,
      OtlpConfig config,
      RequestLogMetrics metrics,
      long initialRetryDelayMillis,
      long maxRetryDelayMillis,
      long closeTimeoutMillis) {
    if (config.endpoint() == null || config.endpoint().isBlank()) {
      throw new IllegalArgumentException("Request log destination " + name + ": endpoint missing");
    }
    URI uri = URI.create(config.endpoint());
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": endpoint must be an http or https URL");
    }
    if (config.batchBytes() != 0 && config.batchBytes() < OtlpConfig.MIN_BATCH_BYTES) {
      throw new IllegalArgumentException(
          "Request log destination "
              + name
              + ": batchBytes must be at least "
              + OtlpConfig.MIN_BATCH_BYTES);
    }
    if (config.effectiveMaxBufferedBytes() < config.effectiveBatchBytes()) {
      throw new IllegalArgumentException(
          "Request log destination " + name + ": maxBufferedBytes must be at least batchBytes");
    }
    this.name = name;
    endpoint = uri;
    headers = config.headers() != null ? Map.copyOf(config.headers()) : Map.of();
    timeout = Duration.ofMillis(config.effectiveTimeoutMillis());
    batchBytes = config.effectiveBatchBytes();
    maxBufferedBytes = config.effectiveMaxBufferedBytes();
    flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(config.effectiveFlushIntervalMillis());
    this.initialRetryDelayMillis = initialRetryDelayMillis;
    this.maxRetryDelayMillis = maxRetryDelayMillis;
    this.closeTimeoutMillis = closeTimeoutMillis;
    this.metrics = metrics;
    // cached, so counting does not allocate the label array
    dropped = metrics.dropped.labelValues(name);
    exportFailed = metrics.exportFailed.labelValues(name);
    exportRetries = metrics.exportRetries.labelValues(name);
    resourceField =
        new ProtobufWriter()
            .bytes(RESOURCE_LOGS_RESOURCE, encodeResource(config.resourceAttributes()))
            .toByteArray();
    scopeField =
        new ProtobufWriter()
            .message(SCOPE_LOGS_SCOPE, new ProtobufWriter(32).string(SCOPE_NAME, SCOPE))
            .toByteArray();
    scopeSchemaField =
        new ProtobufWriter().string(SCOPE_LOGS_SCHEMA_URL, OtelLogFields.SCHEMA_URL).toByteArray();
    resourceSchemaField =
        new ProtobufWriter()
            .string(RESOURCE_LOGS_SCHEMA_URL, OtelLogFields.SCHEMA_URL)
            .toByteArray();
    client = HttpClient.newBuilder().connectTimeout(timeout).build();
    metrics.add(this);
    sender = new Thread(this::run, "request-log-" + name);
    // must not keep the JVM alive, close() makes sure nothing is lost on a regular shutdown
    sender.setDaemon(true);
    sender.start();
  }

  /// Encoded `Resource` message. By default with `service.name`, `service.instance.id` (the host
  /// name) and `service.version` (the edge version, if known); configured attributes override
  /// them.
  static byte[] encodeResource(Map<String, String> configured) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put(OtelLogFields.SERVICE_NAME, DEFAULT_SERVICE_NAME);
    String instance = instanceId();
    if (instance != null) {
      attributes.put(OtelLogFields.SERVICE_INSTANCE_ID, instance);
    }
    String version = OtlpLogDestination.class.getPackage().getImplementationVersion();
    if (version != null) {
      attributes.put(OtelLogFields.SERVICE_VERSION, version);
    }
    if (configured != null) {
      attributes.putAll(configured);
    }
    ProtobufWriter resource = new ProtobufWriter();
    attributes.forEach((key, value) -> stringAttribute(resource, RESOURCE_ATTRIBUTES, key, value));
    return resource.toByteArray();
  }

  /// Identifies this edge instance: the `HOSTNAME` environment variable, which is the pod name in
  /// Kubernetes, else the local host name
  ///
  /// @return the id, or `null` if none can be determined
  static String instanceId() {
    String hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      return null;
    }
  }

  /// Creates and registers the logger of the calling thread, see [OtlpRequestLogger]
  OtlpThreadLogger newThreadLogger() {
    OtlpThreadLogger threadLogger =
        new OtlpThreadLogger(this, Thread.currentThread(), handOff(null));
    threadLoggers.add(threadLogger);
    return threadLogger;
  }

  /// Queues a filled batch and returns an empty one, from the pool or new.
  ///
  /// @param full filled batch, or `null` to get an empty batch only
  /// @return an empty batch, or `null` if the waiting batches would exceed the limit, then the
  ///   caller keeps the full batch
  synchronized OtlpBatch handOff(OtlpBatch full) {
    if (full != null) {
      if (pendingBytes + full.size() > maxBufferedBytes) {
        return null;
      }
      enqueue(full);
    }
    OtlpBatch next = free.pollFirst();
    // allocates only while sending lags behind, the pool keeps the batches afterwards
    return next != null ? next : new OtlpBatch(batchBytes);
  }

  private synchronized void enqueue(OtlpBatch batch) {
    pending.addLast(batch);
    pendingBytes += batch.size();
    pendingRecords += batch.records;
  }

  @Override
  public String destination() {
    return name;
  }

  /// Records waiting in the batches of the threads and in the queue
  @Override
  public int queueSize() {
    long records;
    synchronized (this) {
      records = pendingRecords;
    }
    for (OtlpThreadLogger threadLogger : threadLoggers) {
      records += threadLogger.records();
    }
    return (int) Math.min(records, Integer.MAX_VALUE);
  }

  /// Bytes of the batches waiting to be sent
  @Override
  public synchronized long bufferedBytes() {
    return pendingBytes;
  }

  /// Empty batches kept for reuse, for tests
  synchronized int pooledBatches() {
    return free.size();
  }

  public RequestLogMetrics metrics() {
    return metrics;
  }

  int batchBytes() {
    return batchBytes;
  }

  boolean isClosed() {
    return closed;
  }

  void countDropped(long records) {
    dropped.inc(records);
  }

  /// Number of records of this destination dropped since start
  public long droppedCount() {
    return (long) dropped.get();
  }

  /// Wakes the sender thread, a batch is waiting
  void wake() {
    LockSupport.unpark(sender);
  }

  /// Sends what is buffered and stops the sender thread. Waits a few seconds at most, also when
  /// the log server is unavailable, then counts the records left as dropped. Can be called more
  /// than once. Records logged afterwards are dropped.
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    LockSupport.unpark(sender);
    try {
      // the sender stops retrying at the timeout, an ongoing request may take a little longer
      sender.join(closeTimeoutMillis + 1_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// Hands the buffered records to the destination that replaces this one in a configuration
  /// reload, instead of sending them. Returns at once: the sender finishes an attempt in progress
  /// and then moves its waiting batches and those of the threads to the successor, see
  /// [#takeOver(List)]. Records logged after this call are refused by the thread loggers and
  /// passed on by the [OtlpRequestLogger].
  void handOverTo(OtlpLogDestination successor) {
    if (closed) {
      return;
    }
    // registered before the close, so the successor waits for the batches when it closes
    successor.incoming.incrementAndGet();
    this.successor = successor;
    closed = true;
    LockSupport.unpark(sender);
  }

  /// Takes over the batches of the destination this one replaces. They go to the head of the
  /// queue, in their order, so older records are sent first. Batches beyond the limit of the
  /// waiting batches are dropped and counted, the oldest first. When this destination itself was
  /// replaced meanwhile, the batches go on to its successor.
  void takeOver(List<OtlpBatch> batches) {
    try {
      takeOverBatches(batches);
    } finally {
      incoming.decrementAndGet();
    }
  }

  private void takeOverBatches(List<OtlpBatch> batches) {
    long dropped = 0;
    synchronized (this) {
      if (!finished) {
        for (int i = batches.size() - 1; i >= 0; i--) {
          OtlpBatch batch = batches.get(i);
          if (pendingBytes + batch.size() > maxBufferedBytes) {
            dropped += batch.records;
            continue;
          }
          pending.addFirst(batch);
          pendingBytes += batch.size();
          pendingRecords += batch.records;
        }
        batches = null;
      }
    }
    if (batches != null) {
      OtlpLogDestination next = successor;
      if (next != null) {
        // the expected handover moves on to the successor
        next.incoming.incrementAndGet();
        next.takeOver(batches);
        return;
      }
      for (OtlpBatch batch : batches) {
        dropped += batch.records;
      }
    }
    if (dropped > 0) {
      countDropped(dropped);
    }
    wake();
  }

  private void run() {
    long nextFlush = System.nanoTime() + flushIntervalNanos;
    long retryAt = System.nanoTime();
    try {
      while (!closed) {
        long now = System.nanoTime();
        if (now - nextFlush >= 0) {
          handOffPartialBatches();
          nextFlush = now + flushIntervalNanos;
        }
        OtlpBatch head = peek();
        if (head == null || now - retryAt < 0) {
          long wakeAt = head == null || nextFlush - retryAt < 0 ? nextFlush : retryAt;
          LockSupport.parkNanos(this, wakeAt - now);
          continue;
        }
        long delayMillis = attempt(head, timeout);
        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
      }
      OtlpLogDestination next = successor;
      if (next != null) {
        transfer(next);
      } else {
        shutdown();
      }
    } finally {
      client.shutdownNow();
      // e.g. removed by a configuration reload, the gauges stop reporting it
      metrics.remove(this);
    }
  }

  /// Sends the head of the queue once.
  ///
  /// @return `0` if the batch was sent or given up, else the delay before the next attempt
  private long attempt(OtlpBatch head, Duration requestTimeout) {
    Result result = send(head, requestTimeout);
    switch (result.outcome()) {
      case SENT -> {
        recycle(head);
        if (retries > 0) {
          log.info(
              "Request log export to "
                  + endpoint
                  + " recovered after "
                  + retries
                  + " retries, sending the buffered records");
          retries = 0;
        }
        return 0;
      }
      case GIVE_UP -> {
        long records = head.records;
        recycle(head);
        exportFailed.inc();
        countDropped(records);
        if (failureReported.compareAndSet(false, true)) {
          log.error(
              "Request log export to "
                  + endpoint
                  + " failed, dropped a batch: "
                  + result.reason()
                  + ". Further failures are only counted in"
                  + " sensepitch_request_log_export_failed_total");
        }
        return 0;
      }
      default -> {
        exportRetries.inc();
        if (retries == 0) {
          log.warn(
              "Request log export to "
                  + endpoint
                  + " failed: "
                  + result.reason()
                  + ". Buffering records and retrying, waiting batches are limited by"
                  + " maxBufferedBytes");
        }
        long delay =
            retryDelayMillis(
                initialRetryDelayMillis,
                maxRetryDelayMillis,
                retries,
                result.retryAfterMillis(),
                ThreadLocalRandom.current().nextDouble());
        retries++;
        return delay;
      }
    }
  }

  /// Delay before the next attempt: exponential backoff from `initial` up to `max`, of which a
  /// random half is taken, so a fleet of proxies does not retry in lockstep. A longer delay the
  /// server requested via `Retry-After` wins, a shorter one does not shorten the backoff.
  ///
  /// @param retries failed attempts in a row before this one
  /// @param retryAfterMillis delay the server requested, `0` if none
  /// @param random a random value in `[0, 1)`
  /// @return the delay, at least one millisecond
  static long retryDelayMillis(
      long initial, long max, int retries, long retryAfterMillis, double random) {
    long backoff = Math.min(max, initial << Math.min(retries, 20));
    long delay = backoff / 2 + (long) ((backoff / 2 + 1) * random);
    return Math.max(1, Math.max(delay, retryAfterMillis));
  }

  private synchronized OtlpBatch peek() {
    return pending.peekFirst();
  }

  /// Removes the sent or given up head and keeps it for reuse. One batch per thread and one spare
  /// suffice when sending keeps up, more are released, so the memory taken during an outage is
  /// returned.
  private synchronized void recycle(OtlpBatch head) {
    // not removeFirst: a takeover may have put batches before it while it was being sent
    pending.removeFirstOccurrence(head);
    pendingBytes -= head.size();
    pendingRecords -= head.records;
    head.reset();
    if (free.size() <= threadLoggers.size()) {
      free.addLast(head);
    }
  }

  /// Takes the partly filled batches of all threads. A thread that has terminated cannot log
  /// anymore, its logger is retired and removed.
  private void handOffPartialBatches() {
    for (OtlpThreadLogger threadLogger : threadLoggers) {
      if (threadLogger.owner().isAlive()) {
        threadLogger.handOffPartial();
      } else {
        retire(threadLogger);
        threadLoggers.remove(threadLogger);
      }
    }
  }

  /// Queues the last batch of the thread logger, regardless of the limit, it is one batch at most
  private void retire(OtlpThreadLogger threadLogger) {
    OtlpBatch last = threadLogger.retire();
    if (last != null && last.records > 0) {
      enqueue(last);
    }
  }

  /// Moves all waiting batches, including the last ones of the threads, to the successor. First
  /// waits for the batches of destinations this one replaced, so a chain of quick reloads hands
  /// everything on together. A predecessor may still be in a request, so the wait is bounded by a
  /// request timeout; batches arriving later go on to the successor directly.
  private void transfer(OtlpLogDestination next) {
    awaitIncoming(
        System.nanoTime() + timeout.toNanos() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis));
    for (OtlpThreadLogger threadLogger : threadLoggers) {
      retire(threadLogger);
    }
    threadLoggers.clear();
    List<OtlpBatch> batches;
    long records;
    synchronized (this) {
      batches = new ArrayList<>(pending);
      records = pendingRecords;
      pending.clear();
      free.clear();
      pendingBytes = 0;
      pendingRecords = 0;
      finished = true;
    }
    if (records > 0) {
      log.info(
          "Request log destination "
              + name
              + " replaced, handing "
              + records
              + " buffered records over to its replacement");
    }
    next.takeOver(batches);
  }

  /// Waits until the handovers of replaced destinations into this one have arrived, at most until
  /// the deadline
  private void awaitIncoming(long deadlineNanos) {
    while (incoming.get() > 0 && deadlineNanos - System.nanoTime() > 0) {
      LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(1));
    }
  }

  /// Sends the waiting batches until the close timeout, then counts the rest as dropped. Waits
  /// for the batches of destinations this one replaced, they may still be on their way.
  private void shutdown() {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis);
    awaitIncoming(deadline);
    for (OtlpThreadLogger threadLogger : threadLoggers) {
      retire(threadLogger);
    }
    threadLoggers.clear();
    OtlpBatch head;
    while ((head = peek()) != null) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      long delayMillis = attempt(head, Duration.ofNanos(Math.min(remaining, timeout.toNanos())));
      if (delayMillis > 0) {
        LockSupport.parkNanos(
            this, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(delayMillis)));
      }
    }
    long left;
    synchronized (this) {
      left = pendingRecords;
      pending.clear();
      free.clear();
      pendingBytes = 0;
      pendingRecords = 0;
      finished = true;
    }
    if (left > 0) {
      countDropped(left);
      log.error(
          "Request log export to "
              + endpoint
              + " unavailable at shutdown, dropped "
              + left
              + " buffered records");
    }
  }

  enum Outcome {
    SENT,
    RETRY,
    GIVE_UP
  }

  /// Outcome of one attempt
  ///
  /// @param retryAfterMillis delay the server asked for, `0` if none
  record Result(Outcome outcome, String reason, long retryAfterMillis) {

    static final Result SENT = new Result(Outcome.SENT, null, 0);
  }

  /// Posts the records of the batch, wrapped into an `ExportLogsServiceRequest`. The buffer is
  /// passed to the HTTP client as it is, only the enclosing messages are encoded per request.
  private Result send(OtlpBatch batch, Duration requestTimeout) {
    ProtobufWriter records = batch.writer;
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", CONTENT_TYPE)
            .POST(
                HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(prefix(records.size())),
                    HttpRequest.BodyPublishers.ofByteArray(records.array(), 0, records.size()),
                    HttpRequest.BodyPublishers.ofByteArray(scopeSchemaField),
                    HttpRequest.BodyPublishers.ofByteArray(resourceSchemaField)));
    headers.forEach(builder::header);
    try {
      HttpResponse<Void> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return Result.SENT;
      }
      String reason = "HTTP status " + status;
      if (status == 429 || status == 502 || status == 503 || status == 504) {
        return new Result(Outcome.RETRY, reason, retryAfterMillis(response));
      }
      return new Result(Outcome.GIVE_UP, reason, 0);
    } catch (IOException e) {
      return new Result(Outcome.RETRY, e.toString(), 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(Outcome.RETRY, "interrupted", 0);
    } catch (RuntimeException e) {
      return new Result(Outcome.GIVE_UP, "unexpected " + e, 0);
    }
  }

  /// Delay requested by the server in seconds, the HTTP date form is not supported
  static long retryAfterMillis(HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(OtlpLogDestination::parseRetryAfter)
        .orElse(0L);
  }

  static long parseRetryAfter(String value) {
    try {
      long seconds = Math.max(0, Long.parseLong(value.trim()));
      return Math.min(MAX_RETRY_AFTER_MILLIS, TimeUnit.SECONDS.toMillis(seconds));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /// The enclosing messages up to the records, their lengths depend on the size of the records
  byte[] prefix(int recordBytes) {
    int scopeLogsLength = scopeField.length + recordBytes + scopeSchemaField.length;
    int resourceLogsLength =
        resourceField.length
            + ProtobufWriter.varintSize((long) RESOURCE_LOGS_SCOPE_LOGS << 3)
            + ProtobufWriter.varintSize(scopeLogsLength)
            + scopeLogsLength
            + resourceSchemaField.length;
    ProtobufWriter prefix = new ProtobufWriter(resourceField.length + scopeField.length + 32);
    prefix.lengthDelimited(EXPORT_RESOURCE_LOGS, resourceLogsLength);
    prefix.raw(resourceField);
    prefix.lengthDelimited(RESOURCE_LOGS_SCOPE_LOGS, scopeLogsLength);
    prefix.raw(scopeField);
    return prefix.toByteArray();
  }
}
