package io.github.tinnet.wowfam.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Reads {@code wowfam.toml}:
 *
 * <pre>{@code
 * [settings]
 * default_game = "classic_era"
 *
 * [games.classic_era]
 * dir = "~/Games/World of Warcraft/_classic_era_"
 *
 * [games.classic_era.addons.spoken]
 * source = "github:rusty-key/spoken-wow"
 * version = "latest"
 * tag_prefix = "spoken/"
 * asset = "Spoken-*.zip"
 *
 * [hooks]
 * pre_install = "./review-addon.sh"
 * }</pre>
 *
 * Relative paths are resolved against the directory holding the file; a leading {@code ~} is the
 * user's home.
 */
public final class ConfigLoader {

  private static final Set<String> TOP_KEYS = Set.of("settings", "games", "hooks");
  private static final Set<String> SETTINGS_KEYS = Set.of("cache_dir", "default_game");
  private static final Set<String> GAME_KEYS = Set.of("dir", "addons_dir", "addons");
  private static final Set<String> COMMON_KEYS = Set.of("source", "version", "depends", "hooks");
  private static final Set<String> EXTERNAL_KEYS = Set.of("external", "folders");
  private static final List<String> HOOK_KEYS = List.of("pre_install", "post_install");
  private static final TomlMapper TOML = TomlMapper.builder().build();

  /** What an older wowfam.toml, with one AddOns folder and a top-level [addons], is told. */
  static final String OLD_LAYOUT =
      "keeps its addons in a top-level [addons] table, from before wowfam.toml held several"
          + " games; run wowfam init --import <that file> to move them into the global config";

  private final Map<String, String> env;
  private final Path home;

  public ConfigLoader() {
    this(System.getenv(), Path.of(System.getProperty("user.home")));
  }

  public ConfigLoader(Map<String, String> env, Path home) {
    this.env = env;
    this.home = home;
  }

  public Workspace load(Path file) {
    return load(file, Optional.empty(), null);
  }

  /**
   * Reads {@code file}.
   *
   * @param game the game named on the command line ({@code --game}), if any
   * @param addonsDir replaces the addons folder of that game, or of the one {@link Workspace#one}
   *     picks (the {@code --addons-dir} option)
   */
  public Workspace load(Path file, Optional<String> game, @Nullable Path addonsDir) {
    String text;
    try {
      text = Files.readString(file);
    } catch (NoSuchFileException e) {
      throw new ConfigException(
          "no config at " + file.toAbsolutePath() + "; run wowfam init to create one");
    } catch (IOException e) {
      throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
    }
    return parse(file.toAbsolutePath().normalize(), text, game, addonsDir);
  }

  public Workspace parse(Path file, String toml) {
    return parse(file, toml, Optional.empty(), null);
  }

  public Workspace parse(Path file, String toml, Optional<String> game, @Nullable Path addonsDir) {
    var root = tree(file, toml);
    if (root.has("addons") || root.path("settings").has("addons_dir")) {
      throw new ConfigException(file + " " + OLD_LAYOUT);
    }
    for (var key : root.propertyNames()) {
      if (!TOP_KEYS.contains(key)) {
        throw new ConfigException(
            "unknown table '" + key + "' in " + file + " (expected settings, games or hooks)");
      }
    }
    var base = file.getParent() == null ? Path.of("").toAbsolutePath() : file.getParent();
    var settingsNode = table(root, "settings", file);
    for (var key : settingsNode.propertyNames()) {
      if (!SETTINGS_KEYS.contains(key)) {
        throw new ConfigException("unknown setting settings." + key + " in " + file);
      }
    }
    var cacheDir =
        settingsNode.path("cache_dir").isString()
            ? resolve(base, settingsNode.path("cache_dir").asString())
            : defaultCacheDir();
    var defaultGame =
        settingsNode.path("default_game").isString()
            ? Optional.of(settingsNode.path("default_game").asString())
            : Optional.<String>empty();
    var hooks = hooks("hooks", root.path("hooks"));
    var gamesNode = table(root, "games", file);
    for (var name : gamesNode.propertyNames()) {
      if (!ConfigEditor.NAME.matcher(name).matches()) {
        throw new ConfigException(
            "games.\""
                + name
                + "\" in "
                + file
                + ": game names may only use letters, digits, - and _");
      }
    }
    defaultGame.ifPresent(d -> {
      if (!gamesNode.has(d)) {
        throw new ConfigException(
            "settings.default_game is '" + d + "', but " + file + " has no [games." + d + "]");
      }
    });
    // --addons-dir goes to the game the command will work on.
    var overridden =
        addonsDir == null
            ? Optional.<String>empty()
            : game.or(() -> defaultGame)
                .or(() ->
                    gamesNode.size() == 1
                        ? Optional.of(gamesNode.propertyNames().iterator().next())
                        : Optional.empty());
    if (addonsDir != null && overridden.isEmpty()) {
      throw new ConfigException("--addons-dir needs --game: which game's AddOns is it?");
    }
    var games = new LinkedHashMap<String, Config>();
    for (var entry : gamesNode.properties()) {
      var name = entry.getKey();
      var override = overridden.filter(name::equals).map(n -> addonsDir).orElse(null);
      games.put(name, game(file, base, name, entry.getValue(), cacheDir, hooks, override));
    }
    return new Workspace(file, defaultGame, games);
  }

  private Config game(
      Path file,
      Path base,
      String name,
      JsonNode node,
      Path cacheDir,
      Hooks hooks,
      @Nullable Path override) {
    var where = "games." + name;
    if (!node.isObject()) {
      throw new ConfigException(where + " must be a table in " + file);
    }
    for (var key : node.propertyNames()) {
      if (!GAME_KEYS.contains(key)) {
        throw new ConfigException(
            "unknown key "
                + where
                + "."
                + key
                + " in "
                + file
                + " (expected dir or addons_dir, and addons; an addon is [games."
                + name
                + ".addons.<name>])");
      }
    }
    boolean hasDir = node.path("dir").isString();
    boolean hasAddonsDir = node.path("addons_dir").isString();
    if (override == null && hasDir == hasAddonsDir) {
      throw new ConfigException(
          where
              + " in "
              + file
              + " needs either dir (the game folder, such as .../World of Warcraft/_retail_) or"
              + " addons_dir (its AddOns folder)");
    }
    var gameDir =
        hasDir ? Optional.of(resolve(base, node.path("dir").asString())) : Optional.<Path>empty();
    var addonsDir =
        override != null
            ? override.toAbsolutePath().normalize()
            : hasDir
                ? gameDir.orElseThrow().resolve("Interface").resolve("AddOns")
                : resolve(base, node.path("addons_dir").asString());
    var settings =
        new Settings(
            addonsDir,
            cacheDir,
            Game.detect(addonsDir),
            override != null ? Optional.empty() : gameDir);
    var addons = new LinkedHashMap<String, AddonSpec>();
    var external = new LinkedHashMap<String, ExternalAddon>();
    var addonsNode = node.path("addons");
    if (!addonsNode.isMissingNode() && !addonsNode.isObject()) {
      throw new ConfigException(where + ".addons must be a table in " + file);
    }
    for (var entry : addonsNode.properties()) {
      // Names become folder names (staging, cache), so nothing that could leave a folder.
      if (!ConfigEditor.NAME.matcher(entry.getKey()).matches()) {
        throw new ConfigException(
            where
                + ".addons.\""
                + entry.getKey()
                + "\" in "
                + file
                + ": addon names may only use letters, digits, - and _");
      }
      if (entry.getValue().has("external")) {
        external.put(
            entry.getKey(),
            external(where + ".addons." + entry.getKey(), entry.getKey(), entry.getValue()));
      } else {
        addons.put(
            entry.getKey(),
            addon(where + ".addons", entry.getKey(), entry.getValue(), settings.game()));
      }
    }
    var claimed = new LinkedHashMap<String, String>();
    for (var e : external.values()) {
      for (var folder : e.folders()) {
        var other = claimed.putIfAbsent(folder.toLowerCase(Locale.ROOT), e.name());
        if (other != null) {
          throw new ConfigException(
              where
                  + ".addons."
                  + other
                  + " and "
                  + e.name()
                  + " both list folder "
                  + folder
                  + " in "
                  + file);
        }
      }
    }
    for (var spec : addons.values()) {
      for (var dep : spec.depends()) {
        if (!addons.containsKey(dep) && !external.containsKey(dep)) {
          throw new ConfigException(
              where
                  + ".addons."
                  + spec.name()
                  + " depends on '"
                  + dep
                  + "', which is not an addon of game "
                  + name
                  + " in "
                  + file);
        }
      }
    }
    return new Config(file, name, settings, hooks, addons, external);
  }

  /** An entry with {@code external = "<manager>"} (or {@code true}) and its {@code folders}. */
  private static ExternalAddon external(String where, String name, JsonNode node) {
    for (var key : node.propertyNames()) {
      if (!EXTERNAL_KEYS.contains(key)) {
        throw new ConfigException(
            where
                + " is external, so it takes only external and folders, not "
                + key
                + " (wowfam doesn't install it)");
      }
    }
    var value = node.path("external");
    Optional<String> manager;
    if (value.isBoolean() && value.asBoolean()) {
      manager = Optional.empty();
    } else if (value.isString() && !value.asString().isBlank()) {
      manager = Optional.of(value.asString().strip());
    } else {
      throw new ConfigException(
          where + ".external must be true or who manages the addon, e.g. external = \"WowUp\"");
    }
    var foldersNode = node.path("folders");
    var folders = new ArrayList<String>();
    if (foldersNode.isArray()) {
      for (var f : foldersNode) {
        if (!f.isString() || !isFolderName(f.asString())) {
          throw new ConfigException(
              where + ".folders must list folder names in AddOns, such as \"ElvUI\"");
        }
        if (!folders.contains(f.asString())) {
          folders.add(f.asString());
        }
      }
    }
    if (folders.isEmpty()) {
      throw new ConfigException(
          where
              + " needs folders: the folders in AddOns it stands for, e.g. folders = [\"ElvUI\"]");
    }
    return new ExternalAddon(name, manager, folders);
  }

  /**
   * An addon folder directly in AddOns: no separators, nothing that leaves the folder, and not
   * hidden (WoW doesn't load those, and wowfam stages installs in them).
   */
  public static boolean isFolderName(String name) {
    return !name.isBlank()
        && !name.startsWith(".")
        && name.chars().noneMatch(c -> c == '/' || c == '\\' || c == ':' || c < 0x20);
  }

  private static JsonNode tree(Path file, String toml) {
    try {
      return TOML.readTree(toml);
    } catch (JacksonException e) {
      throw new ConfigException("invalid TOML in " + file + ": " + e.getOriginalMessage(), e);
    }
  }

  private static JsonNode table(JsonNode root, String key, Path file) {
    var node = root.path(key);
    if (!node.isMissingNode() && !node.isObject()) {
      throw new ConfigException("'" + key + "' must be a table in " + file);
    }
    return node;
  }

  /**
   * The addons of a {@code wowfam.toml} from before games, by name, for {@code init --import}: just
   * enough to check that an import kept them all.
   */
  public static Map<String, Source> oldLayoutSources(Path file, String toml) {
    var sources = new LinkedHashMap<String, Source>();
    for (var entry : tree(file, toml).path("addons").properties()) {
      if (entry.getValue().path("source").isString()) {
        sources.put(entry.getKey(), Source.parse(entry.getValue().path("source").asString()));
      }
    }
    return sources;
  }

  /** The AddOns folder a {@code wowfam.toml} from before games installed into. */
  public Path oldLayoutAddonsDir(Path file, String toml) {
    var base = file.toAbsolutePath().getParent();
    var dir = tree(file, toml).path("settings").path("addons_dir");
    return dir.isString()
        ? resolve(base == null ? Path.of("") : base, dir.asString())
        : (base == null ? Path.of("AddOns") : base.resolve("AddOns"));
  }

  private AddonSpec addon(String prefix, String name, JsonNode node, Optional<Game> game) {
    var where = prefix + "." + name;
    if (!node.isObject()) {
      throw new ConfigException(where + " must be a table");
    }
    var sourceNode = node.path("source");
    if (!sourceNode.isString()) {
      throw new ConfigException(
          where
              + " needs a source, e.g. source = \"github:owner/repo\" (or external = true, for an"
              + " addon wowfam leaves to another manager)");
    }
    var source = Source.parse(sourceNode.asString());
    var version =
        node.path("version").isMissingNode()
            ? AddonSpec.LATEST
            : scalar(where, "version", node.path("version"));
    var depends = new ArrayList<String>();
    var dependsNode = node.path("depends");
    if (dependsNode.isArray()) {
      dependsNode.forEach(d -> depends.add(d.asString()));
    } else if (dependsNode.isString()) {
      depends.add(dependsNode.asString());
    } else if (!dependsNode.isMissingNode()) {
      throw new ConfigException(where + ".depends must be a list of addon names");
    }
    var options = new LinkedHashMap<String, String>();
    for (var entry : node.properties()) {
      if (!COMMON_KEYS.contains(entry.getKey())) {
        options.put(entry.getKey(), scalar(where, entry.getKey(), entry.getValue()));
      }
    }
    // The game folder says which client the addons are for; a flavor or interface written in the
    // file takes precedence.
    if (game.isPresent() && !options.containsKey("flavor") && !options.containsKey("interface")) {
      options.put("interface", Integer.toString(game.orElseThrow().interfaceNumber()));
    }
    var hooks = hooks(where + ".hooks", node.path("hooks"));
    return new AddonSpec(name, source, version, depends, options, hooks);
  }

  private static Hooks hooks(String where, JsonNode node) {
    if (node.isMissingNode()) {
      return Hooks.NONE;
    }
    if (!node.isObject()) {
      throw new ConfigException(where + " must be a table");
    }
    for (var entry : node.properties()) {
      if (!HOOK_KEYS.contains(entry.getKey())) {
        throw new ConfigException(
            "unknown hook "
                + where
                + "."
                + entry.getKey()
                + " (expected "
                + String.join(" or ", HOOK_KEYS)
                + ")");
      }
    }
    return new Hooks(
        commands(where + ".pre_install", node.path("pre_install")),
        commands(where + ".post_install", node.path("post_install")));
  }

  /** A command, or a list of commands run in order. */
  private static List<String> commands(String where, JsonNode node) {
    if (node.isMissingNode()) {
      return List.of();
    }
    if (node.isString()) {
      return List.of(node.asString());
    }
    if (node.isArray()) {
      var commands = new ArrayList<String>();
      for (var element : node) {
        if (!element.isString()) {
          throw new ConfigException(where + " must be a command or a list of commands");
        }
        commands.add(element.asString());
      }
      return commands;
    }
    throw new ConfigException(where + " must be a command or a list of commands");
  }

  private static String scalar(String where, String key, JsonNode value) {
    if (!value.isValueNode()) {
      throw new ConfigException(where + "." + key + " must be a string, number or boolean");
    }
    return value.asString();
  }

  private Path resolve(Path base, String path) {
    if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
      return home.resolve(path.length() <= 2 ? "" : path.substring(2)).normalize();
    }
    return base.resolve(path).normalize();
  }

  private Path defaultCacheDir() {
    var xdg = env.get("XDG_CACHE_HOME");
    if (xdg != null && !xdg.isBlank()) {
      return Path.of(xdg, "wowfam");
    }
    var localAppData = env.get("LOCALAPPDATA");
    if (localAppData != null && !localAppData.isBlank()) {
      return Path.of(localAppData, "wowfam", "cache");
    }
    return home.resolve(".cache").resolve("wowfam");
  }
}
