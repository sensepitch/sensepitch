package org.sensepitch.edge;

import java.util.Map;
import java.util.TreeMap;

/// Maps string prefixes to values. A lookup returns the value of the longest prefix of the key,
/// independent of the insertion order.
///
/// @author Jens Wilke
public class PrefixTree<V> {

  private final TreeMap<String, V> tree = new TreeMap<>();

  /// Adds a prefix, replacing a previous value of the same prefix.
  public void put(String prefix, V value) {
    tree.put(prefix, value);
  }

  public boolean isEmpty() {
    return tree.isEmpty();
  }

  /// Returns the value of the longest prefix of `key`, or `null` if no prefix matches.
  public V lookup(String key) {
    Map.Entry<String, V> entry = tree.floorEntry(key);
    while (entry != null) {
      String prefix = entry.getKey();
      if (key.startsWith(prefix)) {
        return entry.getValue();
      }
      // Any matching prefix is a prefix of the common part of the floor entry and the key, and
      // is ordered not after it, so continue from there. The common part is strictly shorter than
      // the floor entry, which ensures termination.
      entry = tree.floorEntry(key.substring(0, commonPrefixLength(prefix, key)));
    }
    return null;
  }

  private static int commonPrefixLength(String a, String b) {
    int n = Math.min(a.length(), b.length());
    int i = 0;
    while (i < n && a.charAt(i) == b.charAt(i)) {
      i++;
    }
    return i;
  }
}
