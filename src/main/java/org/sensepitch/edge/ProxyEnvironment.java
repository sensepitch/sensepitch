package org.sensepitch.edge;

import io.netty.util.NettyRuntime;
import io.netty.util.internal.SystemPropertyUtil;

/// What configuration defaults may depend on besides the configuration itself, e.g. the default
/// of [OtlpConfig#maxConcurrentRequests()]. A config record whose defaults depend on it has a
/// `builder(ProxyEnvironment)`, which the configuration loaders prefer. Tests pass a fixed
/// environment to get the same values on every machine.
///
/// @param eventLoopThreads threads of an event loop group without explicit size, the number of
///   event loops of the [Proxy]
/// @author Jens Wilke
public record ProxyEnvironment(int eventLoopThreads) {

  /// The environment of this JVM: the event loop threads as Netty's `MultithreadEventLoopGroup`
  /// computes them, the system property `io.netty.eventLoopThreads` or two per processor
  public static ProxyEnvironment current() {
    return new ProxyEnvironment(
        Math.max(
            1,
            SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2)));
  }
}
