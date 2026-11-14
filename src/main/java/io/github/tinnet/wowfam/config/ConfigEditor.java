package io.github.tinnet.wowfam.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Changes {@code wowfam.toml} as text, so the user's comments and layout stay as they wrote them.
 */
public final class ConfigEditor {

  /** Addon names that can be a bare TOML key, and so be written {@code [addons.<name>]}. */
  public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

  /** Options written in this order after {@code source} and {@code version}; others follow. */
  private static final List<String> OPTION_ORDER =
      List.of("tag_prefix", "asset", "flavor", "nolib", "prerelease");

  private ConfigEditor() {}

  /** The {@code [addons.<name>]} table for {@code spec}, ending in a newline. */
  public static String addonTable(AddonSpec spec) {
    if (!NAME.matcher(spec.name()).matches()) {
      throw new ConfigException(
          "'" + spec.name() + "' cannot be an addon name: use letters, digits, - and _");
    }
    var table = new StringBuilder("[addons.").append(spec.name()).append("]\n");
    line(table, "source", spec.source().toString());
    line(table, "version", spec.version());
    var keys = new ArrayList<>(OPTION_ORDER);
    spec.options().keySet().stream()
        .filter(k -> !OPTION_ORDER.contains(k))
        .sorted()
        .forEach(keys::add);
    for (var key : keys) {
      spec.option(key).ifPresent(value -> line(table, key, value));
    }
    return table.toString();
  }

  /** {@code toml} with {@code table} appended after a blank line. */
  public static String append(String toml, String table) {
    if (toml.isBlank()) {
      return table;
    }
    var text = toml.endsWith("\n") ? toml : toml + "\n";
    return text + (text.endsWith("\n\n") ? "" : "\n") + table;
  }

  /**
   * {@code toml} without the {@code [addons.<name>]} table, its sub-tables (such as {@code
   * [addons.<name>.hooks]}) and the comment lines directly above them. Empty if the file doesn't
   * hold the addon as such a table (an inline table, or dotted keys under {@code [addons]}).
   *
   * <p>Works on lines, not a parse, so everything else stays as written. The caller should parse
   * the result to check that only the addon went.
   */
  public static Optional<String> removeAddon(String toml, String name) {
    var own =
        Pattern.compile(
            "\\s*\\[\\s*addons\\s*\\.\\s*(?:"
                + Pattern.quote(name)
                + "|\""
                + Pattern.quote(name)
                + "\"|'"
                + Pattern.quote(name)
                + "')\\s*(?:\\..*)?\\]\\s*(?:#.*)?");
    var lines = List.of(toml.split("\n", -1));
    var drop = new boolean[lines.size()];
    boolean found = false;
    for (int i = 0; i < lines.size(); ) {
      if (!own.matcher(lines.get(i)).matches()) {
        i++;
        continue;
      }
      found = true;
      int end = i + 1;
      while (end < lines.size() && !isHeader(lines.get(end))) {
        end++;
      }
      // Comments right above the next table are about that table, unless it is ours too; the blank
      // lines before them stay too, and the extra one is collapsed below.
      int keepFrom = end;
      if (end < lines.size() && !own.matcher(lines.get(end)).matches()) {
        while (keepFrom - 1 > i && isComment(lines.get(keepFrom - 1))) {
          keepFrom--;
        }
        while (keepFrom - 1 > i && lines.get(keepFrom - 1).isBlank()) {
          keepFrom--;
        }
      }
      for (int j = i; j < keepFrom; j++) {
        drop[j] = true;
      }
      // Comments directly above the header describe it, if they form their own paragraph (a
      // comment line glued to the previous table's last key may be about that key instead).
      int top = i;
      while (top > 0 && isComment(lines.get(top - 1)) && !drop[top - 1]) {
        top--;
      }
      if (top < i && (top == 0 || lines.get(top - 1).isBlank())) {
        for (int j = top; j < i; j++) {
          drop[j] = true;
        }
      }
      i = end;
    }
    if (!found) {
      return Optional.empty();
    }
    var out = new StringBuilder();
    boolean afterDrop = false;
    for (int i = 0; i < lines.size(); i++) {
      if (drop[i]) {
        afterDrop = true;
        continue;
      }
      // One blank line where the table was, not two.
      if (afterDrop && lines.get(i).isBlank() && endsWithBlankLine(out)) {
        continue;
      }
      afterDrop &= lines.get(i).isBlank();
      out.append(lines.get(i)).append('\n');
    }
    var text = out.toString().stripTrailing();
    return Optional.of(text.isEmpty() ? "" : text + "\n");
  }

  private static boolean endsWithBlankLine(StringBuilder out) {
    if (out.isEmpty()) {
      return true; // nothing above: a blank line would start the file
    }
    int end = out.length() - 1; // the newline ending the last line
    int start = out.lastIndexOf("\n", end - 1) + 1;
    return out.substring(start, end).isBlank();
  }

  private static boolean isHeader(String line) {
    return line.stripLeading().startsWith("[");
  }

  private static boolean isComment(String line) {
    return line.stripLeading().startsWith("#");
  }

  /**
   * Replaces {@code file} with {@code text} in one move, so a crash leaves one or the other. A
   * symlinked file is written where the link points, and keeps its permissions.
   */
  public static void write(Path file, String text) {
    try {
      var target = Files.exists(file) ? file.toRealPath() : file;
      var temp = target.resolveSibling(target.getFileName() + ".tmp");
      Files.writeString(temp, text);
      if (Files.exists(target)
          && Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
        Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
      }
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new ConfigException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private static void line(StringBuilder table, String key, String value) {
    table.append(key).append(" = ");
    if (value.equals("true") || value.equals("false")) {
      table.append(value);
    } else {
      quoted(table, value);
    }
    table.append('\n');
  }

  /** A TOML basic string. */
  static void quoted(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        case '\r' -> out.append("\\r");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append("\\u%04x".formatted((int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
