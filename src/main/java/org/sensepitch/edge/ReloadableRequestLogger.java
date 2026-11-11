package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Passes each request to all request log destinations, and replaces the destinations when the
/// configuration is reloaded, see [#prepare(RequestLogConfig)].
///
/// A destination whose name and configuration are unchanged is kept, so an OTLP destination keeps
/// its buffered records and its sender thread. A changed OTLP destination hands its buffered
/// records over to its replacement of the same name, and records an event loop still logs into it
/// after the switch are passed on as well, so a reload loses nothing, see
/// [OtlpRequestLogger#handOverTo]. A removed destination is closed, which sends what it buffered
/// within the close timeout.
///
/// The destinations are held in an array that is replaced as a whole, logging a request reads it
/// once and does not allocate.
///
/// @author Jens Wilke
public final class ReloadableRequestLogger implements RequestLogger {

  /// A destination with the configuration it was built from
  ///
  /// @param otlp the OTLP logger inside `logger`, `null` for other types
  record Destination(
      String name,
      RequestLogDestinationConfig config,
      RequestLogger logger,
      OtlpRequestLogger otlp) {

    Destination(String name, RequestLogDestinationConfig config, RequestLogger logger) {
      this(name, config, logger, otlpOf(logger));
    }

    private static OtlpRequestLogger otlpOf(RequestLogger logger) {
      if (logger instanceof AnonymizingRequestLogger anonymizing) {
        logger = anonymizing.delegate();
      }
      return logger instanceof OtlpRequestLogger otlp ? otlp : null;
    }
  }

  private final RequestLogOutput stdout;
  private final RequestLogMetrics metrics;
  private volatile Destination[] destinations = new Destination[0];

  /// Creates the destinations of the configuration
  ///
  /// @throws IllegalArgumentException if the configuration is invalid
  ReloadableRequestLogger(
      RequestLogConfig config, RequestLogOutput stdout, RequestLogMetrics metrics) {
    this.stdout = stdout;
    this.metrics = metrics;
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
  /// @param config request log configuration, `null` for the defaults
  /// @throws IllegalArgumentException if the configuration is invalid; the destinations created
  ///   until then are closed, the active ones stay untouched
  public Prepared prepare(RequestLogConfig config) {
    List<RequestLogDestinationConfig> configs = RequestLoggerFactory.destinations(config);
    List<String> names = RequestLoggerFactory.names(configs);
    Destination[] current = destinations;
    Destination[] next = new Destination[configs.size()];
    List<Destination> created = new ArrayList<>();
    try {
      for (int i = 0; i < next.length; i++) {
        Destination unchanged = find(current, names.get(i), configs.get(i));
        if (unchanged != null) {
          next[i] = unchanged;
        } else {
          next[i] =
              new Destination(
                  names.get(i),
                  configs.get(i),
                  RequestLoggerFactory.createDestination(
                      names.get(i), configs.get(i), stdout, metrics));
          created.add(next[i]);
        }
      }
    } catch (RuntimeException e) {
      // stop the destinations created here, no thread is left behind
      created.forEach(destination -> destination.logger().close());
      throw e;
    }
    return new Prepared(next);
  }

  private static Destination find(
      Destination[] destinations, String name, RequestLogDestinationConfig config) {
    for (Destination destination : destinations) {
      if (destination.name().equals(name) && Objects.equals(destination.config(), config)) {
        return destination;
      }
    }
    return null;
  }

  /// Closes all destinations, writing out what is buffered
  @Override
  public void close() {
    for (Destination destination : destinations) {
      destination.logger().close();
    }
  }

  /// Destinations built by [#prepare(RequestLogConfig)], not yet active
  public final class Prepared {

    private final Destination[] next;

    private Prepared(Destination[] next) {
      this.next = next;
    }

    /// Activates the destinations. A replaced OTLP destination hands over to its successor of the
    /// same name, other destinations that were not taken over are closed. Does not wait for a log
    /// server.
    public void apply() {
      Destination[] previous = destinations;
      destinations = next;
      for (Destination old : previous) {
        if (contains(next, old)) {
          continue;
        }
        Destination successor = successorOf(old);
        if (successor != null) {
          old.otlp().handOverTo(successor.otlp(), successor.logger());
        } else {
          old.logger().close();
        }
      }
    }

    /// The new OTLP destination of the same name, or `null`
    private Destination successorOf(Destination old) {
      if (old.otlp() == null) {
        return null;
      }
      for (Destination candidate : next) {
        if (candidate.name().equals(old.name()) && candidate.otlp() != null) {
          return candidate;
        }
      }
      return null;
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
