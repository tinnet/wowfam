package io.github.tinnet.wowfam.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.regex.Pattern.MULTILINE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds World of Warcraft installs where the Battle.net launcher usually puts them: the default
 * folders on Windows and macOS, and on Linux the Wine prefixes of Lutris, Steam (Proton), Bottles,
 * Heroic, Faugus and plain Wine.
 */
public final class WowInstalls {

  /**
   * A game folder of an install.
   *
   * @param name the name {@code init} gives it in wowfam.toml: the folder without its underscores,
   *     {@code _classic_era_} -> {@code classic_era}
   * @param dir the game folder, e.g. {@code .../World of Warcraft/_retail_}
   * @param game the client in it, if the launcher's files say
   */
  public record Found(String name, Path dir, Optional<Game> game) {}

  /** {@code _retail_}, {@code _classic_era_}, {@code _ptr_}, {@code _forever_}... */
  private static final Pattern GAME_FOLDER = Pattern.compile("_([A-Za-z0-9_]+)_");

  private static final String WOW = "World of Warcraft";

  private static final Pattern LUTRIS_PREFIX =
      Pattern.compile("^\\s*prefix:\\s*['\"]?(.+?)['\"]?\\s*$", MULTILINE);

  private WowInstalls() {}

  /** The games of every install found in the usual places, each once. */
  public static List<Found> find(String os, Map<String, String> env, Path home) {
    var found = new LinkedHashMap<Path, Found>();
    for (var candidate : candidates(os, env, home)) {
      for (var game : gamesIn(candidate)) {
        found.putIfAbsent(realPath(game.dir()), game);
      }
    }
    return List.copyOf(found.values());
  }

  /**
   * The games {@code dir} holds: the game folders of an install, the game folder itself, or the
   * game an AddOns folder belongs to.
   */
  public static List<Found> gamesIn(Path dir) {
    var d = dir.toAbsolutePath().normalize();
    if (!Files.isDirectory(d)) {
      return List.of();
    }
    var name = d.getFileName() == null ? "" : d.getFileName().toString();
    if (name.equalsIgnoreCase("AddOns")
        && d.getParent() != null
        && d.getParent().getParent() != null) {
      return gamesIn(d.getParent().getParent());
    }
    if (isGameFolder(d)) {
      return List.of(found(d));
    }
    List<Path> folders;
    try (Stream<Path> children = Files.list(d)) {
      folders = children.filter(WowInstalls::isGameFolder).sorted().toList();
    } catch (IOException e) {
      return List.of();
    }
    // The launcher leaves a game's folder behind when the game is uninstalled, without its
    // .flavor.info; an install that has any game with one only has those.
    var installed =
        folders.stream().filter(f -> Files.isRegularFile(f.resolve(Game.FLAVOR_INFO))).toList();
    return (installed.isEmpty() ? folders : installed).stream().map(WowInstalls::found).toList();
  }

  /** {@code _classic_era_} -> {@code classic_era}. */
  static String gameName(String folder) {
    var m = GAME_FOLDER.matcher(folder);
    var name = m.matches() ? m.group(1) : folder;
    name = name.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
    return name.isEmpty() ? "wow" : name;
  }

  private static boolean isGameFolder(Path dir) {
    var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
    return Files.isDirectory(dir)
        && (Files.isRegularFile(dir.resolve(Game.FLAVOR_INFO))
            || (GAME_FOLDER.matcher(name).matches()
                && Files.isDirectory(dir.resolve("Interface"))));
  }

  private static Found found(Path dir) {
    var folder = dir.getFileName() == null ? "" : dir.getFileName().toString();
    return new Found(
        gameName(folder), dir, Game.detect(dir.resolve("Interface").resolve("AddOns")));
  }

  /** Folders a WoW install may be in, most likely first; most of them won't exist. */
  static List<Path> candidates(String os, Map<String, String> env, Path home) {
    var candidates = new ArrayList<Path>();
    if (os.startsWith("Windows")) {
      windowsRegistry().ifPresent(candidates::add);
      for (var root : home.getFileSystem().getRootDirectories()) {
        candidates.add(root.resolve("Program Files (x86)").resolve(WOW));
        candidates.add(root.resolve("Program Files").resolve(WOW));
        candidates.add(root.resolve(WOW));
        candidates.add(root.resolve("Games").resolve(WOW));
      }
      return candidates;
    }
    if (os.startsWith("Mac")) {
      candidates.add(Path.of("/Applications", WOW));
      candidates.add(home.resolve("Applications").resolve(WOW));
    }
    candidates.add(home.resolve("Games").resolve(WOW));
    for (var prefix : winePrefixes(env, home)) {
      var driveC = prefix.resolve("drive_c");
      candidates.add(driveC.resolve("Program Files (x86)").resolve(WOW));
      candidates.add(driveC.resolve("Program Files").resolve(WOW));
    }
    return candidates;
  }

  /** Wine prefixes of the launchers people run Battle.net with on Linux. */
  static List<Path> winePrefixes(Map<String, String> env, Path home) {
    var config = xdg(env, "XDG_CONFIG_HOME", home.resolve(".config"));
    var data = xdg(env, "XDG_DATA_HOME", home.resolve(".local/share"));
    var prefixes = new ArrayList<Path>();
    // Lutris: ~/Games/<game> by default; the game's YAML says where if it was moved.
    prefixes.addAll(children(home.resolve("Games")));
    for (var dir : List.of(config.resolve("lutris/games"), data.resolve("lutris/games"))) {
      for (var yml : children(dir)) {
        if (yml.toString().endsWith(".yml")) {
          lutrisPrefix(yml, home).ifPresent(prefixes::add);
        }
      }
    }
    prefixes.add(home.resolve(".wine"));
    // Steam: Battle.net added as a non-Steam game runs in a Proton prefix per app id.
    for (var steam :
        List.of(
            home.resolve(".steam/steam"),
            home.resolve(".steam/root"),
            data.resolve("Steam"),
            home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"),
            home.resolve(".var/app/com.valvesoftware.Steam/data/Steam"))) {
      for (var app : children(steam.resolve("steamapps/compatdata"))) {
        prefixes.add(app.resolve("pfx"));
      }
    }
    prefixes.addAll(children(data.resolve("bottles/bottles")));
    prefixes.addAll(children(home.resolve(".var/app/com.usebottles.bottles/data/bottles/bottles")));
    prefixes.addAll(children(home.resolve("Games/Heroic/Prefixes/default")));
    prefixes.addAll(children(home.resolve("Games/Heroic/Prefixes")));
    prefixes.addAll(children(home.resolve("Faugus")));
    return prefixes;
  }

  private static Optional<Path> lutrisPrefix(Path yml, Path home) {
    try {
      var m = LUTRIS_PREFIX.matcher(Files.readString(yml));
      if (!m.find()) {
        return Optional.empty();
      }
      var prefix = m.group(1);
      return Optional.of(
          prefix.startsWith("~/") ? home.resolve(prefix.substring(2)) : Path.of(prefix));
    } catch (IOException | RuntimeException e) {
      return Optional.empty(); // a launcher's file we can't read just isn't a hint
    }
  }

  /** {@code InstallPath} of the launcher's registry key, which names the retail folder. */
  private static Optional<Path> windowsRegistry() {
    try {
      var process =
          new ProcessBuilder(
                  "reg",
                  "query",
                  "HKLM\\SOFTWARE\\WOW6432Node\\Blizzard Entertainment\\World of Warcraft",
                  "/v",
                  "InstallPath")
              .redirectErrorStream(true)
              .start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readAllBytes(), UTF_8);
      }
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        return Optional.empty();
      }
      var m = Pattern.compile("InstallPath\\s+REG_SZ\\s+(.+)").matcher(output);
      if (!m.find()) {
        return Optional.empty();
      }
      var path = Path.of(m.group(1).strip());
      // ...\World of Warcraft\_retail_\ : the install is one up.
      var name = path.getFileName() == null ? "" : path.getFileName().toString();
      return Optional.of(
          GAME_FOLDER.matcher(name).matches() && path.getParent() != null
              ? path.getParent()
              : path);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private static Path xdg(Map<String, String> env, String variable, Path fallback) {
    var value = env.get(variable);
    return value == null || value.isBlank() ? fallback : Path.of(value);
  }

  private static List<Path> children(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> children = Files.list(dir)) {
      return children.sorted().toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path;
    }
  }
}
