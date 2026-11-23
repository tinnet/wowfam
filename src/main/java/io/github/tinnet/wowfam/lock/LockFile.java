package io.github.tinnet.wowfam.lock;

import static java.util.Collections.unmodifiableMap;

import io.github.tinnet.wowfam.util.Checksum;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * {@code wowfam.lock}, beside {@code wowfam.toml}: for each game, the exact release installed for
 * each addon and the folders it put in the AddOns directory. Like {@code mise.lock}, {@code
 * install} reproduces it and {@code update} moves it forward.
 *
 * <p>An instance is one game's part of the file; {@link #write} keeps the other games' entries as
 * they were read.
 */
public final class LockFile {

  private static final TomlMapper TOML = TomlMapper.builder().build();

  private final Path path;
  private final String game;
  private final Map<String, LockEntry> entries;
  private final Map<String, Map<String, LockEntry>> otherGames;

  /** Whether the text this was parsed from is in an older format than {@link #toToml} writes. */
  private final boolean outdatedFormat;

  private LockFile(
      Path path,
      String game,
      Map<String, LockEntry> entries,
      Map<String, Map<String, LockEntry>> otherGames,
      boolean outdatedFormat) {
    this.path = path;
    this.game = game;
    this.entries = new TreeMap<>(entries);
    this.otherGames = new TreeMap<>(otherGames);
    this.otherGames.remove(game);
    this.outdatedFormat = outdatedFormat;
  }

  /**
   * Reads {@code game}'s part of the lock file, rewriting the file in the current format if it is
   * in an older one.
   */
  public static LockFile read(Path path, String game) {
    if (!Files.exists(path)) {
      return new LockFile(path, game, Map.of(), Map.of(), false);
    }
    try {
      var lock = parse(path, game, Files.readString(path));
      if (lock.outdatedFormat) {
        lock.write();
      }
      return lock;
    } catch (IOException e) {
      throw new LockFileException("cannot read " + path + ": " + e.getMessage(), e);
    }
  }

  static LockFile parse(Path path, String game, String toml) {
    var root = tree(path, toml);
    if (root.has("addons")) {
      throw new LockFileException(
          path
              + " is from before wowfam.toml held several games; wowfam init --import moves it"
              + " along with its wowfam.toml");
    }
    var games = new TreeMap<String, Map<String, LockEntry>>();
    boolean outdatedFormat = false;
    for (var g : root.path("games").properties()) {
      var parsed = new Parsed();
      games.put(g.getKey(), entries(path, g.getValue().path("addons"), parsed));
      outdatedFormat |= parsed.outdatedFormat;
    }
    return new LockFile(path, game, games.getOrDefault(game, Map.of()), games, outdatedFormat);
  }

  /**
   * The entries of a {@code wowfam.lock} from before games (a top-level {@code [addons]}), for
   * {@code init --import}.
   */
  public static Map<String, LockEntry> readOldLayout(Path path) {
    try {
      return entries(path, tree(path, Files.readString(path)).path("addons"), new Parsed());
    } catch (IOException e) {
      throw new LockFileException("cannot read " + path + ": " + e.getMessage(), e);
    }
  }

  private static final class Parsed {
    boolean outdatedFormat;
  }

  private static JsonNode tree(Path path, String toml) {
    try {
      return TOML.readTree(toml);
    } catch (JacksonException e) {
      throw new LockFileException("invalid lock file " + path + ": " + e.getOriginalMessage(), e);
    }
  }

  private static Map<String, LockEntry> entries(Path path, JsonNode addons, Parsed parsed) {
    var entries = new TreeMap<String, LockEntry>();
    for (var e : addons.properties()) {
      var n = e.getValue();
      var folders = new ArrayList<String>();
      n.path("folders").forEach(f -> folders.add(f.asString()));
      Optional<Checksum> checksum = Optional.empty();
      if (n.path("checksum").isString()) {
        var text = n.path("checksum").asString();
        checksum =
            Optional.of(
                Checksum.parse(text)
                    .orElseThrow(() ->
                        new LockFileException(
                            "invalid lock file "
                                + path
                                + ": checksum of "
                                + e.getKey()
                                + " is not <algorithm>:<hex>")));
      } else if (n.path("sha256").isString()) {
        // Before the algorithm was recorded, the only digest was `sha256 = "<hex>"`.
        checksum = Checksum.parse(Checksum.SHA256 + ":" + n.path("sha256").asString());
        parsed.outdatedFormat = true;
      }
      entries.put(
          e.getKey(),
          new LockEntry(
              n.path("source").asString(),
              n.path("version").asString(),
              n.path("tag").asString(),
              n.path("asset").asString(),
              n.path("url").asString(),
              checksum,
              folders));
    }
    return entries;
  }

  public Path path() {
    return path;
  }

  public Optional<LockEntry> get(String addon) {
    return Optional.ofNullable(entries.get(addon));
  }

  public Map<String, LockEntry> entries() {
    return unmodifiableMap(entries);
  }

  public void put(String addon, LockEntry entry) {
    entries.put(addon, entry);
  }

  public void remove(String addon) {
    entries.remove(addon);
  }

  /**
   * The addons in this lock that own {@code folder}. Normally at most one; an entry left behind by
   * a renamed or deleted addon can share a folder with the addon that took it over.
   */
  public List<String> owners(String folder) {
    return entries.entrySet().stream()
        .filter(e -> e.getValue().folders().contains(folder))
        .map(Map.Entry::getKey)
        .toList();
  }

  /**
   * One {@code [games.<game>.addons.<name>]} table per addon, sorted by game and name, so diffs
   * stay small.
   */
  public String toToml() {
    var all = new TreeMap<String, Map<String, LockEntry>>(otherGames);
    all.put(game, entries);
    var sb =
        new StringBuilder(
            "# Generated by wowfam. Records what is installed; commit it to reproduce an install.\n");
    all.forEach((g, addons) ->
        addons.forEach((name, e) -> {
          sb.append("\n[games.").append(key(g)).append(".addons.").append(key(name)).append("]\n");
          line(sb, "source", e.source());
          line(sb, "version", e.version());
          line(sb, "tag", e.tag());
          line(sb, "asset", e.asset());
          line(sb, "url", e.url());
          e.checksum().ifPresent(c -> line(sb, "checksum", c.toString()));
          sb.append("folders = [")
              .append(String.join(", ", e.folders().stream().map(LockFile::string).toList()))
              .append("]\n");
        }));
    return sb.toString();
  }

  private static void line(StringBuilder sb, String key, String value) {
    sb.append(key).append(" = ").append(string(value)).append('\n');
  }

  private static String key(String key) {
    return key.matches("[A-Za-z0-9_-]+") ? key : string(key);
  }

  /** A TOML basic string. */
  static String string(String value) {
    var sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\t' -> sb.append("\\t");
        case '\r' -> sb.append("\\r");
        default -> {
          // The TOML parser takes a byte order mark anywhere but the start for a stray one.
          if (c < 0x20 || c == 0x7f || c == '\uFEFF') {
            sb.append("\\u%04X".formatted((int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  public void write() {
    try {
      var tmp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(tmp, toToml());
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new LockFileException("cannot write " + path + ": " + e.getMessage(), e);
    }
  }
}
