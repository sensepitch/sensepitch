package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class ConfigDiffTest {

  static final String BASE =
      """
      sites:
        a.com:
          response:
            text: A
        b.com:
          response:
            text: B
      """;

  @Test
  void identical() {
    assertThat(ConfigDiff.diff(BASE, BASE)).isEmpty();
  }

  @Test
  void changeShowsEnclosingLines() {
    String changed = BASE.replace("text: B", "text: C");
    assertThat(ConfigDiff.diff(BASE, changed))
        .isEqualTo(
            """
              sites:
                b.com:
                  response:
            -       text: B
            +       text: C
            """);
  }

  @Test
  void insertion() {
    String inserted = BASE + "  c.com:\n    response:\n      text: C\n";
    assertThat(ConfigDiff.diff(BASE, inserted))
        .isEqualTo(
            """
              sites:
            +   c.com:
            +     response:
            +       text: C
            """);
  }

  @Test
  void deletion() {
    String deleted = BASE.replace("  b.com:\n    response:\n      text: B\n", "");
    assertThat(ConfigDiff.diff(BASE, deleted))
        .isEqualTo(
            """
              sites:
            -   b.com:
            -     response:
            -       text: B
            """);
  }

  @Test
  void topLevelChangeHasNoContext() {
    assertThat(ConfigDiff.diff("a: 1\nb: 2\n", "a: 1\nb: 3\n")).isEqualTo("- b: 2\n+ b: 3\n");
  }
}
