package org.sensepitch.edge;

import java.net.InetAddress;

/// Looks up the complete traits for an IP address.
@FunctionalInterface
public interface IpTraitsLookup {
  /// Lookup used when no IP traits sources are configured.
  IpTraitsLookup NONE = emptyLookup();

  /// Creates the no-op lookup with one shared immutable result.
  private static IpTraitsLookup emptyLookup() {
    IpTraits empty = IpTraits.builder().build();
    return address -> empty;
  }

  IpTraits lookup(InetAddress address) throws Exception;

  /// Adapts a collector to a lookup that builds a fresh result for each address.
  static IpTraitsLookup fromCollector(IpTraitsCollector collector) {
    return address -> {
      var builder = IpTraits.builder();
      collector.collect(builder, address);
      return builder.build();
    };
  }
}
