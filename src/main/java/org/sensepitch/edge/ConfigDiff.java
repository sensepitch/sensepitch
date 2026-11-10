package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.List;

/// Line based diff of two YAML documents, for logging configuration changes.
///
/// Changed lines are prefixed with `- ` or `+ `. Unchanged lines are left out, except the
/// enclosing (less indented) lines of a change, which are prefixed with two spaces, so the
/// position of a change in the YAML structure is visible.
///
/// @author Jens Wilke
public class ConfigDiff {

  /// @return the diff, or an empty string if both are identical
  public static String diff(String oldText, String newText) {
    String[] a = oldText.split("\n");
    String[] b = newText.split("\n");
    // lcs[i][j]: length of the longest common subsequence of a[i..] and b[j..]
    int[][] lcs = new int[a.length + 1][b.length + 1];
    for (int i = a.length - 1; i >= 0; i--) {
      for (int j = b.length - 1; j >= 0; j--) {
        lcs[i][j] =
            a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    StringBuilder sb = new StringBuilder();
    // lines of each side seen so far, the context is searched in the side of the change
    List<Line> oldSide = new ArrayList<>();
    List<Line> newSide = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i].equals(b[j])) {
        Line line = new Line(a[i], false);
        oldSide.add(line);
        newSide.add(line);
        i++;
        j++;
        continue;
      }
      boolean removed = j == b.length || (i < a.length && lcs[i + 1][j] >= lcs[i][j + 1]);
      Line line = new Line(removed ? a[i++] : b[j++], true);
      List<Line> side = removed ? oldSide : newSide;
      appendContext(sb, side, indent(line.text));
      side.add(line);
      sb.append(removed ? "- " : "+ ").append(line.text).append('\n');
    }
    return sb.toString();
  }

  /// Appends the enclosing unchanged lines not printed yet, outermost first. Enclosing changed
  /// lines are printed already.
  private static void appendContext(StringBuilder sb, List<Line> side, int indent) {
    List<Line> chain = new ArrayList<>();
    for (int k = side.size() - 1; k >= 0 && indent > 0; k--) {
      Line line = side.get(k);
      int lineIndent = indent(line.text);
      if (lineIndent < indent) {
        chain.addFirst(line);
        indent = lineIndent;
      }
    }
    for (Line line : chain) {
      if (!line.printed) {
        line.printed = true;
        sb.append("  ").append(line.text).append('\n');
      }
    }
  }

  private static final class Line {
    final String text;
    boolean printed;

    Line(String text, boolean changed) {
      this.text = text;
      this.printed = changed;
    }
  }

  private static int indent(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == ' ') {
      n++;
    }
    return n;
  }
}
