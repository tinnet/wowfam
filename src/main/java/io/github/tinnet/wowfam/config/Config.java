package io.github.tinnet.wowfam.config;

import static java.util.Collections.unmodifiableMap;

import io.github.tinnet.wowfam.util.DependencyOrder;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One game of a {@code wowfam.toml}: its {@code [games.<name>]} table and the addons in it, in the
 * order they were written.
 *
 * @param file the {@code wowfam.toml} it is part of
 * @param name the game's name in the file, e.g. {@code forever}
 * @param hooks the {@code [hooks]} table, run for every addon before the addon's own hooks
 * @param addons the addons wowfam installs
 * @param external the addons another manager keeps ({@code external = ...}); their names are
 *     distinct from those of {@code addons}
 */
public record Config(
    Path file,
    String name,
    Settings settings,
    Hooks hooks,
    Map<String, AddonSpec> addons,
    Map<String, ExternalAddon> external) {

  public static final String FILE_NAME = "wowfam.toml";
  public static final String LOCK_FILE_NAME = "wowfam.lock";

  public Config {
    addons = unmodifiableMap(new LinkedHashMap<>(addons));
    external = unmodifiableMap(new LinkedHashMap<>(external));
  }

  public Config(
      Path file, String name, Settings settings, Hooks hooks, Map<String, AddonSpec> addons) {
    this(file, name, settings, hooks, addons, Map.of());
  }

  public Config(Path file, String name, Settings settings, Map<String, AddonSpec> addons) {
    this(file, name, settings, Hooks.NONE, addons);
  }

  /** Whether {@code addon} is an entry of this game, managed or external. */
  public boolean has(String addon) {
    return addons.containsKey(addon) || external.containsKey(addon);
  }

  /** The external entry that lists {@code folder}, if any. */
  public Optional<ExternalAddon> externalOwner(String folder) {
    return external.values().stream().filter(e -> e.folders().contains(folder)).findFirst();
  }

  /** The global hooks followed by the addon's own. */
  public Hooks hooksFor(AddonSpec addon) {
    return hooks.then(addon.hooks());
  }

  public Path lockFile() {
    return file.resolveSibling(LOCK_FILE_NAME);
  }

  /**
   * The named addons (or all, if none are named) and everything they depend on, dependencies first.
   */
  public List<AddonSpec> installOrder(Collection<String> names) {
    return closure(names).stream().map(addons::get).filter(Objects::nonNull).toList();
  }

  /**
   * The external addons the named addons depend on, directly or through others; every external
   * addon if none are named.
   */
  public List<ExternalAddon> externalDependencies(Collection<String> names) {
    return closure(names).stream().map(external::get).filter(Objects::nonNull).toList();
  }

  private List<String> closure(Collection<String> names) {
    var graph = new LinkedHashMap<String, List<String>>();
    addons.forEach((name, spec) -> graph.put(name, spec.depends()));
    external.keySet().forEach(name -> graph.put(name, List.of()));
    var roots = new LinkedHashSet<String>(names);
    if (names.isEmpty()) {
      roots.addAll(addons.keySet());
      roots.addAll(external.keySet());
    }
    for (var root : names) {
      if (external.containsKey(root)) {
        throw new ConfigException(
            root
                + " is "
                + external.get(root).describe()
                + " in game "
                + name
                + " of "
                + file
                + ": wowfam does not install or update it");
      }
      if (!addons.containsKey(root)) {
        throw new ConfigException("no addon named '" + root + "' in game " + name + " of " + file);
      }
    }
    try {
      return DependencyOrder.resolve(graph, roots);
    } catch (DependencyOrder.CycleException e) {
      throw new ConfigException(e.getMessage() + " in game " + name + " of " + file, e);
    }
  }
}
