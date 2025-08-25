package org.sensepitch.edge;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Binary trie implementation that returns multiple labels for an ip address
 *
 * @author Jens Wilke
 */
public class IpTrie implements AnyVersionIpLookup {

  private final TrieNode root = new TrieNode();

  /** Insert a CIDR like "66.249.64.0/19" or "2001:4860::/32" */
  @Override
  public void insert(String cidrStr, String label) {
    String[] parts = cidrStr.split("/");
    InetAddress base;
    try {
      base = InetAddress.getByName(parts[0]);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
    int prefixLen = Integer.parseInt(parts[1]);

    byte[] bytes = base.getAddress();
    TrieNode node = root;
    int bitIndex = 0;
    outer:
    for (int i = 0; i < bytes.length && bitIndex < prefixLen; i++) {
      for (int b = 7; b >= 0; b--) {
        int bit = (bytes[i] >> b) & 1;
        node =
            node.children[bit] != null ? node.children[bit] : (node.children[bit] = new TrieNode());
        bitIndex++;
        if (bitIndex == prefixLen) {
          List<String> labelList = new ArrayList<>();
          if (node.labels != null) {
            Collections.addAll(labelList, node.labels);
          }
          labelList.add(label);
          node.labels = labelList.toArray(new String[0]);
          break outer;
        }
      }
    }
  }

  /** Walk the trie along the bits of `addr` and add the found labels */
  @Override
  public List<String> findLabelMatching(byte[] addr) {
    TrieNode node = root;
    List<String> labels = null;
    for (byte value : addr) {
      for (int b = 7; b >= 0; b--) {
        int bit = (value >> b) & 1;
        node = node.children[bit];
        if (node == null) {
          return labels;
        }
        if (node.labels == null) {
          continue;
        }
        if (labels == null) {
          labels = new ArrayList<>();
        }
        Collections.addAll(labels, node.labels);
      }
    }
    return labels;
  }

  @Override
  public int getNodeCount() {
    return getNodeCount(root);
  }

  int getNodeCount(TrieNode node) {
    int count = 1;
    for (TrieNode c : node.children) {
      if (c != null) {
        count += getNodeCount(c);
      }
    }
    return count;
  }

  private static class TrieNode {
    TrieNode[] children = new TrieNode[2];
    String[] labels = null;
  }
}
