package io.github.tinnet.wowfam.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
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

  /** Replaces {@code file} with {@code text} in one move, so a crash leaves one or the other. */
  public static void write(Path file, String text) {
    var temp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Files.writeString(temp, text);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
