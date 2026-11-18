package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// @param admin admin HTTP endpoint, not started if absent, see [AdminServer]
/// @param upstream default upstream for sites without an upstream of their own
/// @param upstreams named upstreams, referenced via [UpstreamConfig#ref()]
/// @param requestLog request log destinations and format, see [RequestLogConfig]
/// @param traceContext handling of the W3C trace context, see [TraceContextConfig]
/// @param shutdownTimeoutMillis upper limit of the time a shutdown takes, e.g. after `SIGTERM`.
///   The shutdown first keeps accepting for `shutdownAcceptGraceMillis`, then stops accepting
///   connections, waits for the requests in flight, then sends the request log, and ends as soon
///   as all is done. The last part, [RequestLogConfig#drainTimeoutMillis()], is reserved for
///   sending the log. `0` means the default of {@value #DEFAULT_SHUTDOWN_TIMEOUT_MILLIS}. Read at
///   shutdown, so a reload changes it.
/// @param shutdownAcceptGraceMillis time the proxy keeps accepting and serving connections after
///   the shutdown started and readiness became false (`/readyz` answers `503`), before the
///   listener closes. It gives endpoint updates and load balancer probes time to notice. Default
///   `0`, the listener closes at once. Part of `shutdownTimeoutMillis`: together with the
///   reserved request log time it must fit into it. Read at shutdown, so a reload changes it.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ProxyConfig(
    MetricsConfig metrics,
    AdminConfig admin,
    ListenConfig listen,
    UnservicedHostConfig unservicedHost,
    IpLookupConfig ipLookup,
    Ja4Config ja4,
    FallbackConfig fallback,
    UpstreamConfig upstream,
    Map<String, UpstreamConfig> upstreams,
    ProtectionConfig protection,
    RequestLogConfig requestLog,
    TraceContextConfig traceContext,
    Map<String, SiteConfig> sites,
    long shutdownTimeoutMillis,
    long shutdownAcceptGraceMillis) {

  public static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000;

  /// The shutdown timeout, with the default applied
  public long effectiveShutdownTimeoutMillis() {
    return shutdownTimeoutMillis > 0 ? shutdownTimeoutMillis : DEFAULT_SHUTDOWN_TIMEOUT_MILLIS;
  }

  /// The part of the shutdown timeout reserved for sending the request log
  ///
  /// @throws IllegalArgumentException if a timeout is negative or the drain timeout exceeds the
  ///   shutdown timeout, or the accept grace leaves less than the reserved log time
  public long effectiveDrainTimeoutMillis() {
    long total = effectiveShutdownTimeoutMillis();
    long drain = requestLog != null ? requestLog.drainTimeoutMillis() : 0;
    if (shutdownTimeoutMillis < 0 || drain < 0 || shutdownAcceptGraceMillis < 0) {
      throw new IllegalArgumentException(
          "shutdownTimeoutMillis, shutdownAcceptGraceMillis and requestLog.drainTimeoutMillis"
              + " must not be negative");
    }
    if (drain > total) {
      throw new IllegalArgumentException(
          "requestLog.drainTimeoutMillis " + drain + " exceeds shutdownTimeoutMillis " + total);
    }
    drain = drain > 0 ? drain : total / 3;
    // subtract instead of adding the two configured values, which could overflow
    if (shutdownAcceptGraceMillis > total - drain) {
      throw new IllegalArgumentException(
          "shutdownAcceptGraceMillis plus requestLog.drainTimeoutMillis exceeds"
              + " shutdownTimeoutMillis "
              + total);
    }
    return drain;
  }
}
