package org.sensepitch.edge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.function.Supplier;

/// Checks whether at least one configured network upstream accepts a plain TCP connection.
///
/// Upstreams are plain HTTP with one connection per request and no pool or circuit breaker, so a
/// short-timeout TCP connect is the probe: it succeeds as soon as an upstream accepts the
/// connection, without sending a request, and short-circuits on the first success. A configuration
/// with only static-response sites has no network target and is ready immediately.
///
/// @author Jens Wilke
public final class UpstreamReadinessProbe {

  /// Connect timeout per target. A readiness check may need several sequential attempts.
  public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1000;

  private final Supplier<Set<String>> targets;
  private final int connectTimeoutMillis;

  /// @param targets the distinct network targets of the active configuration, read per probe so a
  ///   reload applies at once
  public UpstreamReadinessProbe(Supplier<Set<String>> targets) {
    this(targets, DEFAULT_CONNECT_TIMEOUT_MILLIS);
  }

  /// @param targets see [#UpstreamReadinessProbe(Supplier)]
  /// @param connectTimeoutMillis timeout of one TCP connect attempt
  public UpstreamReadinessProbe(Supplier<Set<String>> targets, int connectTimeoutMillis) {
    this.targets = targets;
    this.connectTimeoutMillis = connectTimeoutMillis;
  }

  /// `true` while the configuration has no network target, or as soon as one of them accepts a
  /// plain TCP connection.
  public boolean isReady() {
    Set<String> current = targets.get();
    if (current.isEmpty()) {
      return true;
    }
    for (String target : current) {
      if (reachable(target)) {
        return true;
      }
    }
    return false;
  }

  private boolean reachable(String target) {
    try (Socket socket = new Socket()) {
      socket.connect(addressOf(target), connectTimeoutMillis);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /// Parses `host[:port]`, the port defaults to 80. [DefaultUpstream] validates the same format at
  /// startup, so an invalid target never reaches a probe.
  static InetSocketAddress addressOf(String target) {
    int colon = target.lastIndexOf(':');
    if (colon >= 0) {
      return new InetSocketAddress(
          target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)));
    }
    return new InetSocketAddress(target, 80);
  }
}
