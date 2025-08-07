package org.sensepitch.edge;

import java.util.List;

/**
 * @author Jens Wilke
 */
public interface IpLabelLookup {

  void insertIpv4(String cidr, String label);

  void insertIpv6(String cidr, String label);

  List<String> lookupIpv4(byte[] addr);

  List<String> lookupIpv6(byte[] addr);
}
