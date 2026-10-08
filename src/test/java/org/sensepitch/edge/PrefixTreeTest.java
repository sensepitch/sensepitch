package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class PrefixTreeTest {

  @Test
  void emptyTree() {
    PrefixTree<String> tree = new PrefixTree<>();
    assertThat(tree.isEmpty()).isTrue();
    assertThat(tree.lookup("/api/x")).isNull();
  }

  @Test
  void longestPrefixWinsIndependentOfInsertionOrder() {
    PrefixTree<String> tree = new PrefixTree<>();
    tree.put("/api/v2/", "v2");
    tree.put("/api/", "api");
    tree.put("/", "root");
    assertThat(tree.lookup("/api/v2/x")).isEqualTo("v2");
    assertThat(tree.lookup("/api/v3/x")).isEqualTo("api");
    assertThat(tree.lookup("/api/")).isEqualTo("api");
    assertThat(tree.lookup("/api")).isEqualTo("root");
    assertThat(tree.lookup("/products")).isEqualTo("root");
  }

  @Test
  void siblingPrefixes() {
    PrefixTree<String> tree = new PrefixTree<>();
    tree.put("/a", "a");
    tree.put("/ab", "ab");
    assertThat(tree.lookup("/ac")).isEqualTo("a");
    assertThat(tree.lookup("/abc")).isEqualTo("ab");
    assertThat(tree.lookup("/b")).isNull();
    assertThat(tree.lookup("/")).isNull();
  }

  @Test
  void skipsEntriesBetweenPrefixAndKey() {
    PrefixTree<String> tree = new PrefixTree<>();
    tree.put("/shop/", "shop");
    tree.put("/shop/a", "a");
    tree.put("/shop/ab", "ab");
    tree.put("/shop/b/", "b");
    assertThat(tree.lookup("/shop/c")).isEqualTo("shop");
    assertThat(tree.lookup("/shop/b")).isEqualTo("shop");
    assertThat(tree.lookup("/shop/b/x")).isEqualTo("b");
  }

  @Test
  void emptyPrefixMatchesEverything() {
    PrefixTree<String> tree = new PrefixTree<>();
    tree.put("", "all");
    tree.put("/x", "x");
    assertThat(tree.lookup("")).isEqualTo("all");
    assertThat(tree.lookup("/y")).isEqualTo("all");
    assertThat(tree.lookup("/x")).isEqualTo("x");
  }
}
