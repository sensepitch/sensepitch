package org.sensepitch.edge;

import java.net.InetAddress;

/// Adds traits found for an address to a shared builder.
@FunctionalInterface
public interface IpTraitsCollector {
  void collect(IpTraits.Builder builder, InetAddress address) throws Exception;
}
