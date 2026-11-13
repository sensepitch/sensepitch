package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/// Passes each request to all request log destinations, and replaces the destinations when the
/// configuration is reloaded, see [#prepare(RequestLogConfig)].
///
/// A destination whose name and configuration are unchanged is kept, so an OTLP destination keeps
/// its buffered records. A changed or removed OTLP destination is finished without waiting: each
/// event loop writes its end marker into it after the switch, so the records a loop logged just
/// before arrive as well. It keeps sending its buffered records to its own endpoint and ends when
/// they are sent or have aged out, see [BatchingRequestLogger#finish()]. Other removed
/// destinations are closed.
///
/// At shutdown, [#finish()] covers the current destinations and those still draining from a
/// reload. The event loops write their end markers when they terminate.
///
/// The destinations are held in an array that is replaced as a whole, logging a request reads it
/// once and does not allocate.
///
/// @author Jens Wilke
public final class ReloadableRequestLogger implements RequestLogger {

  /// A destination with the configuration it was built from
  ///
  /// @param batching the batching logger inside `logger`, e.g. OTLP, `null` for other types
  record Destination(
      String name,
      RequestLogDestinationConfig config,
      RequestLogger logger,
      BatchingRequestLogger<?> batching) {

    Destination(String name, RequestLogDestinationConfig config, RequestLogger logger) {
      this(name, config, logger, batchingOf(logger));
    }

    private static BatchingRequestLogger<?> batchingOf(RequestLogger logger) {
      if (logger instanceof AnonymizingRequestLogger anonymizing) {
        logger = anonymizing.delegate();
      }
      return logger instanceof BatchingRequestLogger<?> batching ? batching : null;
    }
  }

  private final RequestLogOutput stdout;
  private final RequestLogMetrics metrics;
  private final Iterable<? extends Executor> loggingThreads;
  private volatile Destination[] destinations = new Destination[0];

  /// Replaced or removed batching destinations, until they stopped
  private final Set<BatchingRequestLogger<?>> draining = ConcurrentHashMap.newKeySet();

  /// Creates the destinations of the configuration
  ///
  /// @param loggingThreads the threads that log requests, the event loops, as executors. A
  ///   reload runs the end marker of a replaced destination on each of them.
  /// @throws IllegalArgumentException if the configuration is invalid
  ReloadableRequestLogger(
      RequestLogConfig config,
      RequestLogOutput stdout,
      RequestLogMetrics metrics,
      Iterable<? extends Executor> loggingThreads) {
    this.stdout = stdout;
    this.metrics = metrics;
    this.loggingThreads = loggingThreads;
    prepare(config).apply();
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    for (Destination destination : destinations) {
      destination.logger().logRequest(info);
    }
  }

  /// The active destinations, for tests
  List<Destination> destinations() {
    return List.of(destinations);
  }

  /// Validates the configuration and builds its destinations, reusing unchanged ones. Nothing is
  /// switched yet, see [Prepared#apply()].
  ///
  /// @param config request log configuration, `null` for the defaults, see
  ///   [RequestLogConfig#disabled()] and [RequestLogDestinationConfig#disabled()]
  /// @throws IllegalArgumentException if the configuration is invalid; the destinations created
  ///   until then are closed, the active ones stay untouched
  public Prepared prepare(RequestLogConfig config) {
    List<RequestLogDestinationConfig> configs = RequestLoggerFactory.destinations(config);
    List<String> names = RequestLoggerFactory.names(configs);
    Destination[] current = destinations;
    List<Destination> next = new ArrayList<>();
    List<Destination> created = new ArrayList<>();
    try {
      for (int i = 0; i < configs.size(); i++) {
        RequestLogDestinationConfig destinationConfig = configs.get(i);
        if (destinationConfig.disabled()) {
          continue;
        }
        Destination destination = find(current, names.get(i), destinationConfig);
        if (destination == null) {
          destination =
              new Destination(
                  names.get(i),
                  destinationConfig,
                  RequestLoggerFactory.createDestination(
                      names.get(i), destinationConfig, stdout, metrics));
          created.add(destination);
        }
        next.add(destination);
      }
    } catch (RuntimeException e) {
      // stop the destinations created here, no thread is left behind
      created.forEach(destination -> destination.logger().close());
      throw e;
    }
    return new Prepared(next.toArray(Destination[]::new));
  }

  /// The active destination with this name and an equal configuration, which a reload keeps,
  /// or `null`
  private static Destination find(
      Destination[] destinations, String name, RequestLogDestinationConfig config) {
    for (Destination destination : destinations) {
      if (destination.name().equals(name) && Objects.equals(destination.config(), config)) {
        return destination;
      }
    }
    return null;
  }

  /// Writes the end marker of the calling thread into all batching destinations, see
  /// [BatchingRequestLogger#endThread()]
  public void endThread() {
    for (Destination destination : destinations) {
      if (destination.batching() != null) {
        destination.batching().endThread();
      }
    }
    for (BatchingRequestLogger<?> batching : draining) {
      batching.endThread();
    }
  }

  /// Finishes all destinations: closes the unbatched ones, e.g. standard out, and finishes the
  /// batching ones, including those still draining from a reload. Does not wait.
  ///
  /// @return completed when all batching destinations stopped: their threads wrote their end
  ///   markers, e.g. because the event loops terminated, and all records were sent or dropped
  public CompletableFuture<Void> finish() {
    List<CompletableFuture<Void>> stopped = new ArrayList<>();
    for (Destination destination : destinations) {
      if (destination.batching() != null) {
        stopped.add(destination.batching().finish());
      } else {
        destination.logger().close();
      }
    }
    for (BatchingRequestLogger<?> batching : draining) {
      stopped.add(batching.finish());
    }
    return CompletableFuture.allOf(stopped.toArray(CompletableFuture[]::new));
  }

  /// Records not sent yet, by destination name, of the destinations that did not stop, for the
  /// shutdown report
  public Map<String, Integer> unsent() {
    Map<String, Integer> unsent = new LinkedHashMap<>();
    List<BatchingRequestLogger<?>> batching = new ArrayList<>(draining);
    for (Destination destination : destinations) {
      if (destination.batching() != null) {
        batching.add(destination.batching());
      }
    }
    for (BatchingRequestLogger<?> logger : batching) {
      HttpBatchSender<?> sender = logger.sender();
      if (!sender.stopped().isDone()) {
        unsent.merge(sender.destination(), sender.queueSize(), Integer::sum);
      }
    }
    return unsent;
  }

  /// Writes the end marker of the calling thread and finishes all destinations, without
  /// waiting, see [#endThread()] and [#finish()]
  @Override
  public void close() {
    endThread();
    finish();
  }

  /// Destinations built by [#prepare(RequestLogConfig)], not yet active
  public final class Prepared {

    private final Destination[] next;

    private Prepared(Destination[] next) {
      this.next = next;
    }

    /// Activates the destinations. Each event loop writes its end marker into a replaced or
    /// removed batching destination, e.g. OTLP, which then finishes: it sends what it buffered in
    /// the background. Other removed destinations are closed. Does not wait for a log server.
    public void apply() {
      Destination[] previous = destinations;
      destinations = next;
      for (Destination old : previous) {
        if (contains(next, old)) {
          continue;
        }
        if (old.batching() != null) {
          retire(old.batching());
        } else {
          old.logger().close();
        }
      }
    }

    /// Runs the end marker on each loop, after the `logRequest` the loop may be in, which read
    /// the old destinations. No later `logRequest` of that loop reaches the destination. Finishes
    /// the destination when all loops ran their marker, so a loop that logs into it for the first
    /// time in that window still gets a batcher.
    private void retire(BatchingRequestLogger<?> batching) {
      draining.add(batching);
      List<CompletableFuture<Void>> markers = new ArrayList<>();
      for (Executor loop : loggingThreads) {
        markers.add(CompletableFuture.runAsync(batching::endThread, loop));
      }
      CompletableFuture.allOf(markers.toArray(CompletableFuture[]::new))
          .thenCompose(done -> batching.finish())
          .thenRun(() -> draining.remove(batching));
    }

    private static boolean contains(Destination[] destinations, Destination destination) {
      for (Destination candidate : destinations) {
        if (candidate == destination) {
          return true;
        }
      }
      return false;
    }
  }
}
