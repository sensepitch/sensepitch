package org.sensepitch.edge;

import java.net.InetAddress;

/**
 * @author Jens Wilke
 */
public interface IpTraitsLookup {

  void lookup(IpTraits.Builder builder, InetAddress address) throws Exception;
}
