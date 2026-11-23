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

  /**
   * Game and addon names that can be a bare TOML key, and so be written {@code
   * [games.<game>.addons.<name>]}.
   */
  public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

  /** Options written in this order after {@code source} and {@code version}; others follow. */
  private static final List<String> OPTION_ORDER =
      List.of("tag_prefix", "asset", "flavor", "nolib", "prerelease");

  private ConfigEditor() {}

  /** The {@code [games.<game>.addons.<name>]} table for {@code spec}, ending in a newline. */
  public static String addonTable(String game, AddonSpec spec) {
    if (!NAME.matcher(spec.name()).matches()) {
      throw new ConfigException(
          "'" + spec.name() + "' cannot be an addon name: use letters, digits, - and _");
    }
    var table =
        new StringBuilder("[games.")
            .append(game)
            .append(".addons.")
            .append(spec.name())
            .append("]\n");
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

  /** The {@code [games.<game>.addons.<name>]} table for an external addon, ending in a newline. */
  public static String externalTable(String game, ExternalAddon addon) {
    if (!NAME.matcher(addon.name()).matches()) {
      throw new ConfigException(
          "'" + addon.name() + "' cannot be an addon name: use letters, digits, - and _");
    }
    var table =
        new StringBuilder("[games.")
            .append(game)
            .append(".addons.")
            .append(addon.name())
            .append("]\n");
    if (addon.manager().isPresent()) {
      table.append("external = ");
      quoted(table, addon.manager().orElseThrow());
      table.append('\n');
    } else {
      table.append("external = true\n");
    }
    table.append("folders = [");
    for (int i = 0; i < addon.folders().size(); i++) {
      if (i > 0) {
        table.append(", ");
      }
      quoted(table, addon.folders().get(i));
    }
    return table.append("]\n").toString();
  }

  /** The {@code [games.<name>]} table for a game in {@code dir}, ending in a newline. */
  public static String gameTable(String name, String dir) {
    if (!NAME.matcher(name).matches()) {
      throw new ConfigException(
          "'" + name + "' cannot be a game name: use letters, digits, - and _");
    }
    var table = new StringBuilder("[games.").append(name).append("]\n");
    line(table, "dir", dir);
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
   * {@code toml} with {@code table} after the last table of {@code game} (so a game's addons stay
   * together), or at the end if the game has none.
   */
  public static String appendToGame(String toml, String game, String table) {
    var own = header("games", game);
    var lines = List.of(toml.split("\n", -1));
    int last = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (own.matcher(lines.get(i)).matches()) {
        last = i;
      }
    }
    if (last < 0) {
      return append(toml, table);
    }
    int end = last + 1;
    while (end < lines.size() && !isHeader(lines.get(end))) {
      end++;
    }
    if (end == lines.size()) {
      return append(toml, table);
    }
    // Before the comments and blank lines that lead up to the next table.
    int at = end;
    while (at - 1 > last && isComment(lines.get(at - 1))) {
      at--;
    }
    while (at - 1 > last && lines.get(at - 1).isBlank()) {
      at--;
    }
    return String.join("\n", lines.subList(0, at))
        + "\n\n"
        + table
        + "\n"
        + String.join("\n", lines.subList(at, lines.size())).stripLeading();
  }

  /**
   * {@code toml} with {@code settings.default_game} set to {@code game}, if it has no default game
   * yet; unchanged otherwise.
   */
  public static String setDefaultGame(String toml, String game) {
    var settings = header("settings");
    var lines = new ArrayList<>(List.of(toml.split("\n", -1)));
    for (int i = 0; i < lines.size(); i++) {
      if (settings.matcher(lines.get(i)).matches()) {
        for (int j = i + 1; j < lines.size() && !isHeader(lines.get(j)); j++) {
          if (lines.get(j).strip().startsWith("default_game")) {
            return toml;
          }
        }
        var entry = new StringBuilder();
        line(entry, "default_game", game);
        lines.add(i + 1, entry.toString().stripTrailing());
        return String.join("\n", lines);
      }
    }
    var table = new StringBuilder("[settings]\n");
    line(table, "default_game", game);
    // Before the first table, and the comments right above it.
    int first = 0;
    while (first < lines.size() && !isHeader(lines.get(first))) {
      first++;
    }
    while (first > 0 && isComment(lines.get(first - 1))) {
      first--;
    }
    if (first >= lines.size() || toml.isBlank()) {
      return append(toml, table.toString());
    }
    return String.join("\n", lines.subList(0, first))
        + (first == 0 ? "" : "\n")
        + table
        + "\n"
        + String.join("\n", lines.subList(first, lines.size()));
  }

  /**
   * The addon tables of a {@code wowfam.toml} from before games ({@code [addons.<name>]} and their
   * sub-tables, with the comments right above them), moved under {@code [games.<game>]}. Its {@code
   * [settings]} and {@code [hooks]} are left out.
   */
  public static String importOldLayout(String toml, String game) {
    var addon = Pattern.compile("(\\s*\\[)\\s*addons\\s*\\.");
    var lines = List.of(toml.split("\n", -1));
    var out = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      var m = addon.matcher(lines.get(i));
      if (!m.lookingAt()) {
        continue;
      }
      int top = i;
      while (top > 0 && isComment(lines.get(top - 1))) {
        top--;
      }
      int end = i + 1;
      while (end < lines.size() && !isHeader(lines.get(end))) {
        end++;
      }
      if (!out.isEmpty()) {
        out.append('\n');
      }
      for (int j = top; j < i; j++) {
        out.append(lines.get(j)).append('\n');
      }
      out.append(m.replaceFirst("$1games." + game + ".addons.")).append('\n');
      var body = String.join("\n", lines.subList(i + 1, end)).strip();
      // Comments at the end of a block lead up to the next table; they come with it.
      var bodyLines = new ArrayList<>(List.of(body.split("\n", -1)));
      while (!bodyLines.isEmpty()
          && (isComment(bodyLines.getLast()) || bodyLines.getLast().isBlank())) {
        bodyLines.removeLast();
      }
      for (var l : bodyLines) {
        out.append(l).append('\n');
      }
      i = end - 1;
    }
    return out.toString();
  }

  /**
   * {@code toml} without the {@code [games.<game>.addons.<name>]} table, its sub-tables (such as
   * {@code [games.<game>.addons.<name>.hooks]}) and the comment lines directly above them. Empty if
   * the file doesn't hold the addon as such a table (an inline table, or dotted keys).
   *
   * <p>Works on lines, not a parse, so everything else stays as written. The caller should parse
   * the result to check that only the addon went.
   */
  public static Optional<String> removeAddon(String toml, String game, String name) {
    return removeTables(toml, header("games", game, "addons", name));
  }

  /**
   * The header lines of the table at {@code keys} and of its sub-tables, with each key bare or
   * quoted.
   */
  private static Pattern header(String... keys) {
    var re = new StringBuilder("\\s*\\[");
    for (int i = 0; i < keys.length; i++) {
      var k = Pattern.quote(keys[i]);
      re.append(i == 0 ? "\\s*" : "\\s*\\.\\s*")
          .append("(?:")
          .append(k)
          .append("|\"")
          .append(k)
          .append("\"|'")
          .append(k)
          .append("')");
    }
    return Pattern.compile(re.append("\\s*(?:\\..*)?\\]\\s*(?:#.*)?").toString());
  }

  private static Optional<String> removeTables(String toml, Pattern own) {
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
