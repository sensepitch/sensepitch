package org.sensepitch.edge;

import java.util.List;

/**
 * TODO: Needs to be consolidated into {@link IpTraitsLookup}
 *
 * @author Jens Wilke
 */
public class TrieIpLabelLookup implements IpLabelLookup {

  AnyVersionIpLookup trieIpv4 = new IpTrie();
  AnyVersionIpLookup trieIpv6 = new IpTrie();

  public AnyVersionIpLookup getIpv4LookupStructure() {
    return trieIpv4;
  }

  public AnyVersionIpLookup getIpv6LookupStructure() {
    return trieIpv6;
  }

  @Override
  public void insertIpv4(String cidr, String label) {
    trieIpv4.insert(cidr, label);
  }

  @Override
  public void insertIpv6(String cidr, String label) {
    trieIpv6.insert(cidr, label);
  }

  @Override
  public List<String> lookupIpv4(byte[] addr) {
    return trieIpv4.findLabelMatching(addr);
  }

  @Override
  public List<String> lookupIpv6(byte[] addr) {
    return trieIpv6.findLabelMatching(addr);
  }

  public int getNodeCount() {
    return trieIpv4.getNodeCount() + trieIpv6.getNodeCount();
  }
}
