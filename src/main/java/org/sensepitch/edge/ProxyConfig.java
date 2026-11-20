package org.sensepitch.edge;

import java.util.Map;
import lombok.Builder;

/// @param admin admin HTTP endpoint, not started if absent, see [AdminServer]
/// @param upstream default upstream for sites without an upstream of their own
/// @param upstreams named upstreams, referenced via [UpstreamConfig#ref()]
/// @param requestLog request log destinations and format, see [RequestLogConfig]. Always set in a
///   built record, with the derived [RequestLogConfig#drainTimeoutMillis()].
/// @param traceContext handling of the W3C trace context, see [TraceContextConfig]
/// @param shutdownTimeoutMillis upper limit of the time a shutdown takes, e.g. after `SIGTERM`.
///   The shutdown first keeps accepting for `shutdownAcceptGraceMillis`, then stops accepting
///   connections, waits for the requests in flight, then sends the request log, and ends as soon
///   as all is done. The last part, [RequestLogConfig#drainTimeoutMillis()], is reserved for
///   sending the log. Defaults to {@value #DEFAULT_SHUTDOWN_TIMEOUT_MILLIS}, must be positive. Read
///   at shutdown, so a reload changes it.
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

  /// Builder with the defaults preset, the derived ones are set by [Builder#build()]
  public static Builder builder() {
    return new Builder().shutdownTimeoutMillis(DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
  }

  /// Rejects a shutdown budget the shutdown cannot keep. Called by the [Proxy] on start and
  /// reload, so an invalid budget is rejected before anything is allocated.
  ///
  /// @throws IllegalArgumentException if a timeout is not positive, the accept grace is negative,
  ///   the drain timeout exceeds the shutdown timeout, or the accept grace leaves less than the
  ///   reserved log time
  void checkShutdownBudget() {
    long total = shutdownTimeoutMillis;
    long drain = requestLog.drainTimeoutMillis();
    if (total <= 0) {
      throw new IllegalArgumentException("shutdownTimeoutMillis must be positive");
    }
    if (drain <= 0) {
      throw new IllegalArgumentException("requestLog.drainTimeoutMillis must be positive");
    }
    if (shutdownAcceptGraceMillis < 0) {
      throw new IllegalArgumentException("shutdownAcceptGraceMillis must not be negative");
    }
    if (drain > total) {
      throw new IllegalArgumentException(
          "requestLog.drainTimeoutMillis " + drain + " exceeds shutdownTimeoutMillis " + total);
    }
    // subtract instead of adding the two configured values, which could overflow
    if (shutdownAcceptGraceMillis > total - drain) {
      throw new IllegalArgumentException(
          "shutdownAcceptGraceMillis plus requestLog.drainTimeoutMillis exceeds"
              + " shutdownTimeoutMillis "
              + total);
    }
  }

  /// Completed by Lombok, which generates everything but [#build()]
  public static class Builder {

    /// Builds the record with the derived defaults: an omitted
    /// [RequestLogConfig#drainTimeoutMillis()] is a third of the shutdown timeout, at least 1 ms,
    /// so a short shutdown timeout does not end up with a drain timeout of zero. It crosses
    /// records, so it is set here and not by the [RequestLogConfig.Builder].
    public ProxyConfig build() {
      RequestLogConfig log = requestLog != null ? requestLog : RequestLogConfig.DEFAULT;
      if (log.drainTimeoutMillis() == null) {
        log = log.toBuilder().drainTimeoutMillis(Math.max(1, shutdownTimeoutMillis / 3)).build();
      }
      return new ProxyConfig(
          metrics,
          admin,
          listen,
          unservicedHost,
          ipLookup,
          ja4,
          fallback,
          upstream,
          upstreams,
          protection,
          log,
          traceContext,
          sites,
          shutdownTimeoutMillis,
          shutdownAcceptGraceMillis);
    }
  }
}
