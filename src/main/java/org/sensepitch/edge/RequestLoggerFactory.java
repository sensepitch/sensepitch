package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Builds the request logger of the configured destinations, see [RequestLogConfig].
///
/// Without configured destinations there is one standard out destination in the format selected
/// by [RequestLogConfig#plainText()], as in earlier versions. All standard out destinations write
/// to the output passed in, which is standard out in production and a capture in tests.
///
/// @author Jens Wilke
public final class RequestLoggerFactory {

  private RequestLoggerFactory() {}

  /// Validates the configuration and creates a logger passing each request to all destinations.
  /// The logger can be reconfigured on a configuration reload, see [ReloadableRequestLogger].
  /// Closing the logger stops the sender threads of `otlp` destinations, the standard out output
  /// is not closed, it belongs to the caller.
  ///
  /// @param config request log configuration, `null` for the defaults
  /// @param stdout output of the `stdout` destinations
  /// @param metrics metrics shared by all destinations, those of the standard out writer if it has
  ///   them, so all destinations register under the same metric names
  /// @throws IllegalArgumentException if the configuration is invalid
  public static ReloadableRequestLogger create(
      RequestLogConfig config, RequestLogOutput stdout, RequestLogMetrics metrics) {
    return new ReloadableRequestLogger(config, stdout, metrics);
  }

  /// The configured destinations, or one standard out destination in the format selected by
  /// [RequestLogConfig#plainText()] if none are configured
  ///
  /// @param config request log configuration, `null` for the defaults
  /// @throws IllegalArgumentException if the destinations are configured but empty
  static List<RequestLogDestinationConfig> destinations(RequestLogConfig config) {
    if (config == null) {
      config = RequestLogConfig.DEFAULT;
    }
    List<RequestLogDestinationConfig> destinations = config.destinations();
    if (destinations == null) {
      return List.of(
          RequestLogDestinationConfig.builder()
              .type(RequestLogDestinationConfig.TYPE_STDOUT)
              .plainText(config.plainText())
              .build());
    }
    if (destinations.isEmpty()) {
      throw new IllegalArgumentException(
          "requestLog.destinations is empty, remove it to log to standard out");
    }
    return destinations;
  }

  /// Creates the logger of one destination, the configuration is validated by [#names(List)]
  ///
  /// @throws IllegalArgumentException if the `otlp` settings are invalid
  static RequestLogger createDestination(
      String name,
      RequestLogDestinationConfig destination,
      RequestLogOutput stdout,
      RequestLogMetrics metrics) {
    RequestLogger logger =
        switch (destination.type()) {
          case RequestLogDestinationConfig.TYPE_STDOUT ->
              destination.plainText()
                  ? new PlainTextRequestLogger(stdout)
                  : new JsonRequestLogger(stdout, destination.sampleHeadersInterval());
          case RequestLogDestinationConfig.TYPE_OTLP ->
              new OtlpRequestLogger(
                  new OtlpLogDestination(
                      name, destination.otlp(), metrics, destination.sampleHeadersInterval()));
          default -> throw new IllegalStateException("type validated before");
        };
    return destination.anonymizeClientIp() ? new AnonymizingRequestLogger(logger) : logger;
  }

  /// Names of the destinations, defaulting to the type, with the index appended for the second
  /// and further `stdout` destinations. Several `otlp` destinations must be named explicitly. Also
  /// validates the type, the presence of the `otlp` settings exactly for `otlp` destinations, that
  /// `sampleHeadersInterval` is not negative, and checks names are unique.
  static List<String> names(List<RequestLogDestinationConfig> destinations) {
    // the name identifies an otlp destination across a reload, a positional default would shift
    // when a destination is inserted and hand a backlog to the wrong endpoint
    boolean severalOtlp =
        destinations.stream()
                .filter(d -> RequestLogDestinationConfig.TYPE_OTLP.equals(d.type()))
                .count()
            > 1;
    Map<String, Integer> typeCount = new HashMap<>();
    Set<String> used = new HashSet<>();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < destinations.size(); i++) {
      RequestLogDestinationConfig destination = destinations.get(i);
      String type = destination.type();
      if (!RequestLogDestinationConfig.TYPE_STDOUT.equals(type)
          && !RequestLogDestinationConfig.TYPE_OTLP.equals(type)) {
        throw new IllegalArgumentException(
            "requestLog.destinations["
                + i
                + "]: unknown type '"
                + type
                + "', expected 'stdout' or 'otlp'");
      }
      boolean otlp = RequestLogDestinationConfig.TYPE_OTLP.equals(type);
      if (otlp && destination.otlp() == null) {
        throw new IllegalArgumentException(
            "requestLog.destinations[" + i + "]: otlp settings missing");
      }
      if (!otlp && destination.otlp() != null) {
        throw new IllegalArgumentException(
            "requestLog.destinations[" + i + "]: otlp settings on a " + type + " destination");
      }
      if (destination.sampleHeadersInterval() < 0) {
        throw new IllegalArgumentException(
            "requestLog.destinations[" + i + "]: sampleHeadersInterval must not be negative");
      }
      String name = destination.name();
      if (otlp && severalOtlp && (name == null || name.isBlank())) {
        throw new IllegalArgumentException(
            "requestLog.destinations["
                + i
                + "]: name required, there are several otlp destinations");
      }
      if (name == null || name.isBlank()) {
        int count = typeCount.merge(type, 1, Integer::sum);
        name = count == 1 ? type : type + (count - 1);
      }
      if (otlp && AsyncRequestLogWriter.STDOUT.equals(name)) {
        throw new IllegalArgumentException(
            "requestLog.destinations[" + i + "]: name 'stdout' is reserved for standard out");
      }
      if (!used.add(name)) {
        throw new IllegalArgumentException(
            "requestLog.destinations[" + i + "]: duplicate name '" + name + "'");
      }
      names.add(name);
    }
    return names;
  }
}
