package io.github.tinnet.wowfam.cli;

import static java.util.Comparator.comparingInt;
import static java.util.function.Predicate.not;

import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigEditor;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.ExternalAddon;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "external",
    mixinStandardHelpOptions = true,
    description = {
      "Record addons that something else keeps (another manager, or you by hand) in wowfam.toml.",
      "wowfam never installs, updates or deletes their folders. It stops listing them as unknown,"
          + " refuses to install another addon over them, lets entries depend on them and warns"
          + " when they are missing. wowfam add with the same name takes one over; wowfam remove"
          + " takes the entry out and leaves the folders."
    })
final class ExternalCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Parameters(
      arity = "0..*",
      paramLabel = "FOLDER",
      description = "Folders in AddOns that make up one addon, e.g. ElvUI ElvUI_Options.")
  List<String> folders = new ArrayList<>();

  @Option(
      names = "--all",
      description =
          "Record every folder in AddOns that wowfam.toml doesn't account for, one entry per"
              + " addon (folders named like another, such as ElvUI_Options, go with it).")
  boolean all;

  @Option(
      names = "--name",
      paramLabel = "NAME",
      description = "Name of the entry (default: the first folder in kebab-case).")
  @Nullable String name;

  @Option(
      names = "--by",
      paramLabel = "MANAGER",
      description = "Who keeps the addon, e.g. WowUp; list shows it.")
  @Nullable String by;

  @Option(
      names = {"-n", "--dry-run"},
      description = "Print the entries without changing wowfam.toml.")
  boolean dryRun;

  @Override
  public Integer call() {
    var config = root.oneGame(root.loadWorkspace());
    var lock = LockFile.read(config.lockFile(), config.name());
    if (all == !folders.isEmpty()) {
      throw new ConfigException("give the folders of one addon, or --all");
    }
    if (all && name != null) {
      throw new ConfigException("--all names its entries itself; --name is for one addon");
    }
    var manager = Optional.ofNullable(by).map(String::strip).filter(not(String::isEmpty));
    var out = spec.commandLine().getOut();
    List<ExternalAddon> entries;
    if (all) {
      var unclaimed = AddOnsFolder.unclaimed(config, lock);
      if (unclaimed.isEmpty()) {
        out.printf("every folder in %s is in wowfam.toml already%n", config.settings().addonsDir());
        return 0;
      }
      var taken = new HashSet<String>(config.addons().keySet());
      taken.addAll(config.external().keySet());
      entries = new ArrayList<>();
      for (var group : groups(unclaimed).entrySet()) {
        var base = AddCommand.kebabCase(group.getKey());
        var entryName = base.isEmpty() ? "addon" : base;
        for (int n = 2; taken.contains(entryName); n++) {
          entryName = (base.isEmpty() ? "addon" : base) + "-" + n;
        }
        taken.add(entryName);
        entries.add(new ExternalAddon(entryName, manager, group.getValue()));
      }
    } else {
      var wanted = List.copyOf(new LinkedHashSet<>(folders));
      for (var folder : wanted) {
        check(config, lock, folder);
      }
      var entryName = name != null ? name : AddCommand.kebabCase(wanted.getFirst());
      if (!ConfigEditor.NAME.matcher(entryName).matches()) {
        throw new ConfigException(
            "'" + entryName + "' cannot be an addon name: use letters, digits, - and _ (--name)");
      }
      if (config.has(entryName)) {
        throw new ConfigException(
            entryName + " is already an addon of game " + config.name() + "; pick another --name");
      }
      entries = List.of(new ExternalAddon(entryName, manager, wanted));
    }

    var tables = entries.stream().map(e -> ConfigEditor.externalTable(config.name(), e)).toList();
    if (dryRun) {
      out.print(String.join("\n", tables));
      return 0;
    }
    var text = read(config);
    for (var table : tables) {
      text = ConfigEditor.appendToGame(text, config.name(), table);
    }
    // As for add: only a file that keeps its addons as tables can take new ones.
    var written = root.loader().parse(config.file(), text).game(config.name()).external();
    if (!entries.stream().allMatch(e -> e.equals(written.get(e.name())))) {
      throw new ConfigException(
          "cannot add external addons to game " + config.name() + " in " + config.file());
    }
    ConfigEditor.write(config.file(), text);
    out.printf("added to %s:%n%s", config.file(), String.join("\n", tables));
    return 0;
  }

  /** Refuses a folder that isn't in AddOns or that an entry already accounts for. */
  private static void check(Config config, LockFile lock, String folder) {
    var addonsDir = config.settings().addonsDir();
    if (!ConfigLoader.isFolderName(folder) || !Files.isDirectory(addonsDir.resolve(folder))) {
      throw new ConfigException("there is no folder " + folder + " in " + addonsDir);
    }
    var external = config.externalOwner(folder);
    if (external.isPresent()) {
      throw new ConfigException(
          folder + " is in the external entry " + external.orElseThrow().name() + " already");
    }
    // A lock entry whose addon is gone from wowfam.toml is uninstalled by the next full run, which
    // leaves external folders alone: those may change hands.
    var owner = lock.owners(folder).stream().filter(config.addons()::containsKey).findFirst();
    if (owner.isPresent()) {
      throw new ConfigException(
          folder
              + " belongs to "
              + owner.orElseThrow()
              + ", which wowfam manages; to hand it over, replace that entry's source and options"
              + " with external = true and its folders");
    }
  }

  /**
   * The folders grouped into addons, by the name of each group: a folder goes with the shortest
   * other folder its name starts with ({@code ElvUI_Options} with {@code ElvUI}, {@code
   * WeakAurasOptions} with {@code WeakAuras}), else with the folders that share the part of its
   * name before the first {@code -} or {@code _} ({@code DBM-Core} and {@code DBM-StatusBarTimers}
   * as {@code DBM}). Groups and their folders keep the order of {@code folders}.
   */
  static Map<String, List<String>> groups(List<String> folders) {
    var keys = new LinkedHashMap<String, String>();
    var groups = new LinkedHashMap<String, List<String>>();
    for (var folder : folders) {
      groups.computeIfAbsent(key(folder, folders, keys), k -> new ArrayList<>()).add(folder);
    }
    return groups;
  }

  /** The group of {@code folder}: that of the shortest folder it starts with, else its stem. */
  private static String key(String folder, List<String> folders, Map<String, String> keys) {
    var known = keys.get(folder);
    if (known != null) {
      return known;
    }
    var prefix =
        folders.stream()
            .filter(other -> other.length() < folder.length() && folder.startsWith(other))
            .min(comparingInt(String::length));
    var key = prefix.isPresent() ? key(prefix.orElseThrow(), folders, keys) : stem(folder);
    keys.put(folder, key);
    return key;
  }

  /** The part of {@code folder} before its first {@code -} or {@code _}, or all of it. */
  private static String stem(String folder) {
    for (int i = 1; i < folder.length(); i++) {
      if (folder.charAt(i) == '-' || folder.charAt(i) == '_') {
        return folder.substring(0, i);
      }
    }
    return folder;
  }

  private static String read(Config config) {
    try {
      return Files.readString(config.file());
    } catch (IOException e) {
      throw new ConfigException("cannot read " + config.file() + ": " + e.getMessage(), e);
    }
  }
}
