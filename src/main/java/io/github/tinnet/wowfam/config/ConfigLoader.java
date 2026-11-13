package io.github.tinnet.wowfam.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * addons_dir = "~/Games/World of Warcraft/_classic_era_/Interface/AddOns"
 *
 * [addons.spoken]
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

  private static final Set<String> COMMON_KEYS = Set.of("source", "version", "depends", "hooks");
  private static final List<String> HOOK_KEYS = List.of("pre_install", "post_install");
  private static final TomlMapper TOML = TomlMapper.builder().build();

  private final Map<String, String> env;
  private final Path home;

  public ConfigLoader() {
    this(System.getenv(), Path.of(System.getProperty("user.home")));
  }

  public ConfigLoader(Map<String, String> env, Path home) {
    this.env = env;
    this.home = home;
  }

  public Config load(Path file) {
    return load(file, null);
  }

  /**
   * Reads {@code file}, with {@code addonsDir} in place of its {@code settings.addons_dir}.
   *
   * @param addonsDir replaces {@code settings.addons_dir} (the {@code --addons-dir} option)
   */
  public Config load(Path file, @Nullable Path addonsDir) {
    String text;
    try {
      text = Files.readString(file);
    } catch (NoSuchFileException e) {
      throw new ConfigException(
          "no "
              + file.getFileName()
              + " found at "
              + file.toAbsolutePath()
              + " (create one, or point to it with --config)");
    } catch (IOException e) {
      throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
    }
    return parse(file.toAbsolutePath().normalize(), text, addonsDir);
  }

  public Config parse(Path file, String toml) {
    return parse(file, toml, null);
  }

  public Config parse(Path file, String toml, @Nullable Path addonsDir) {
    JsonNode root;
    try {
      root = TOML.readTree(toml);
    } catch (JacksonException e) {
      throw new ConfigException("invalid TOML in " + file + ": " + e.getOriginalMessage(), e);
    }
    var base = file.getParent() == null ? Path.of("").toAbsolutePath() : file.getParent();
    var settings = settings(root.path("settings"), base, addonsDir);
    var hooks = hooks("hooks", root.path("hooks"));
    var addons = new LinkedHashMap<String, AddonSpec>();
    var addonsNode = root.path("addons");
    if (!addonsNode.isMissingNode() && !addonsNode.isObject()) {
      throw new ConfigException("'addons' must be a table in " + file);
    }
    for (var entry : addonsNode.properties()) {
      addons.put(entry.getKey(), addon(entry.getKey(), entry.getValue(), settings.game()));
    }
    for (var spec : addons.values()) {
      for (var dep : spec.depends()) {
        if (!addons.containsKey(dep)) {
          throw new ConfigException(
              "addon '"
                  + spec.name()
                  + "' depends on '"
                  + dep
                  + "', which is not defined in "
                  + file);
        }
      }
    }
    return new Config(file, settings, hooks, addons);
  }

  private Settings settings(JsonNode node, Path base, @Nullable Path override) {
    var addonsDir =
        override != null
            ? override.toAbsolutePath().normalize()
            : node.path("addons_dir").isString()
                ? resolve(base, node.path("addons_dir").asString())
                : base.resolve("AddOns");
    var cacheDir =
        node.path("cache_dir").isString()
            ? resolve(base, node.path("cache_dir").asString())
            : defaultCacheDir();
    return new Settings(addonsDir, cacheDir, Game.detect(addonsDir));
  }

  private AddonSpec addon(String name, JsonNode node, Optional<Game> game) {
    if (!node.isObject()) {
      throw new ConfigException("addons." + name + " must be a table");
    }
    var sourceNode = node.path("source");
    if (!sourceNode.isString()) {
      throw new ConfigException(
          "addons." + name + " needs a source, e.g. source = \"github:owner/repo\"");
    }
    var source = Source.parse(sourceNode.asString());
    var version =
        node.path("version").isMissingNode()
            ? AddonSpec.LATEST
            : scalar(name, "version", node.path("version"));
    var depends = new ArrayList<String>();
    var dependsNode = node.path("depends");
    if (dependsNode.isArray()) {
      dependsNode.forEach(d -> depends.add(d.asString()));
    } else if (dependsNode.isString()) {
      depends.add(dependsNode.asString());
    } else if (!dependsNode.isMissingNode()) {
      throw new ConfigException("addons." + name + ".depends must be a list of addon names");
    }
    var options = new LinkedHashMap<String, String>();
    for (var entry : node.properties()) {
      if (!COMMON_KEYS.contains(entry.getKey())) {
        options.put(entry.getKey(), scalar(name, entry.getKey(), entry.getValue()));
      }
    }
    // The game folder says which client the addons are for; a flavor or interface written in the
    // file takes precedence.
    if (game.isPresent() && !options.containsKey("flavor") && !options.containsKey("interface")) {
      options.put("interface", Integer.toString(game.orElseThrow().interfaceNumber()));
    }
    var hooks = hooks("addons." + name + ".hooks", node.path("hooks"));
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

  private static String scalar(String addon, String key, JsonNode value) {
    if (!value.isValueNode()) {
      throw new ConfigException(
          "addons." + addon + "." + key + " must be a string, number or boolean");
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
