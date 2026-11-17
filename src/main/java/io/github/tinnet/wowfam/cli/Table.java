package io.github.tinnet.wowfam.cli;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain-text table whose columns are as wide as their widest cell, two spaces apart. The last
 * column is not padded and an empty one leaves no padding, so lines carry no trailing spaces.
 */
final class Table {

  private final List<List<String>> rows = new ArrayList<>();

  Table(String... header) {
    rows.add(List.of(header));
  }

  Table row(String... cells) {
    if (cells.length != rows.getFirst().size()) {
      throw new IllegalArgumentException(
          "expected " + rows.getFirst().size() + " cells, got " + cells.length);
    }
    rows.add(List.of(cells));
    return this;
  }

  void print(PrintWriter out) {
    int columns = rows.getFirst().size();
    var widths = new int[columns];
    for (var row : rows) {
      for (int i = 0; i < columns; i++) {
        widths[i] = Math.max(widths[i], row.get(i).length());
      }
    }
    for (var row : rows) {
      var line = new StringBuilder();
      for (int i = 0; i < columns - 1; i++) {
        line.append(row.get(i)).repeat(' ', widths[i] - row.get(i).length() + 2);
      }
      // An empty last cell would leave the padding before it.
      out.println(line.append(row.get(columns - 1)).toString().stripTrailing());
    }
  }
}
