package org.sensepitch.edge;

import java.net.InetAddress;

/// Lookup the country for an IP address
///
/// @author Jens Wilke
public interface CountryLookup {
  /// Returns the ISO country code or `null` if not found
  ///
  /// @throws Exception IO errors or other problems
  String lookupCountry(InetAddress addr) throws Exception;
}
