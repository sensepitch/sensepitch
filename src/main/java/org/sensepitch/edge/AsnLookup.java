package org.sensepitch.edge;

import java.net.InetAddress;

/// Lookup the ASN for an IP address
///
/// @author Jens Wilke
public interface AsnLookup {
  /// Returns the ASN or -1 if not found
  ///
  /// @throws Exception IO errors or other problems
  long lookupAsn(InetAddress addr) throws Exception;
}
