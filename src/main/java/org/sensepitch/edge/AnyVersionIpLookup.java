package org.sensepitch.edge;

import java.util.List;

/**
 * @author Jens Wilke
 */
public interface AnyVersionIpLookup {

  void insert(String cidrStr, String label);

  List<String> findLabelMatching(byte[] addr);

  int getNodeCount();
}
