package org.sensepitch.edge;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class IpTrieTest {

  @Test
  public void test() throws UnknownHostException {
    IpTrieWrapper trie = new IpTrieWrapper();
    trie.insert("66.249.77.64/27", "google");
    trie.insert("127.0.0.1/8", "local");
    trie.insert("127.0.0.1/32", "localhost");
    List<String> labels = trie.find("66.249.77.64");
    Assertions.assertThat(trie.find("66.249.77.63")).isNull();
    Assertions.assertThat(trie.find("66.249.77.64")).isNotNull().first().isEqualTo("google");
    Assertions.assertThat(trie.find("66.249.77.69")).isNotNull().first().isEqualTo("google");
    Assertions.assertThat(trie.find("127.0.0.1")).isNotNull().hasSize(2);
  }

  static class IpTrieWrapper implements AnyVersionIpLookup {
    IpTrie trie = new IpTrie();

    @Override
    public void insert(String cidrStr, String label) {
      trie.insert(cidrStr, label);
    }

    public List<String> find(String address) throws UnknownHostException {
      return findLabelMatching(InetAddress.getByName(address).getAddress());
    }

    @Override
    public List<String> findLabelMatching(byte[] addr) {
      return trie.findLabelMatching(addr);
    }

    @Override
    public int getNodeCount() {
      return trie.getNodeCount();
    }
  }
}
