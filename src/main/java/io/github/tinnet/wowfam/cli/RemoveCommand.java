package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigEditor;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Workspace;
import io.github.tinnet.wowfam.install.Installer;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "remove",
    aliases = {"rm", "uninstall"},
    mixinStandardHelpOptions = true,
    description = {
      "Uninstall addons and take them out of wowfam.toml.",
      "With each addon go the folders wowfam.lock records for it, its lock entry and its table in "
          + "wowfam.toml (comments and layout of the rest of the file are kept)."
    })
final class RemoveCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Parameters(arity = "1..*", paramLabel = "ADDON", description = "Addon names from wowfam.toml.")
  List<String> addons = new ArrayList<>();

  @Option(
      names = {"-n", "--dry-run"},
      description = "Print what would be removed without changing anything.")
  boolean dryRun;

  @Override
  public Integer call() {
    var config = root.oneGame(root.loadWorkspace());
    var lock = LockFile.read(config.lockFile(), config.name());
    var names = new LinkedHashSet<>(addons);
    for (var name : names) {
      if (!config.has(name) && lock.get(name).isEmpty()) {
        throw new ConfigException(
            "no addon named '"
                + name
                + "' in game "
                + config.name()
                + " of "
                + config.file()
                + " or "
                + lock.path());
      }
    }
    for (var other : config.addons().values()) {
      if (names.contains(other.name())) {
        continue;
      }
      for (var dep : other.depends()) {
        if (names.contains(dep)) {
          throw new ConfigException(
              other.name()
                  + " depends on "
                  + dep
                  + "; remove "
                  + other.name()
                  + " too, or take "
                  + dep
                  + " out of its depends");
        }
      }
    }
    var edited = withoutEntries(root.loader(), config, names);

    var out = spec.commandLine().getOut();
    if (dryRun) {
      for (var name : names) {
        lock.get(name)
            .ifPresent(e ->
                out.printf(
                    "would uninstall %s %s  (%s)%n",
                    name, e.version(), String.join(", ", e.folders())));
        if (config.addons().containsKey(name)) {
          out.printf("would remove %s from game %s in %s%n", name, config.name(), config.file());
        }
        if (config.external().containsKey(name)) {
          out.printf(
              "would remove %s from game %s in %s; its folders would stay%n",
              name, config.name(), config.file());
        }
      }
      return 0;
    }

    // Folders first: if one can't be deleted (the game has a file open), wowfam.toml still lists
    // the addon, and running remove again finishes the job.
    var outcomes = new ArrayList<Installer.Outcome>();
    for (var name : names) {
      if (lock.get(name).isPresent()) {
        try {
          outcomes.add(
              Installer.uninstall(
                  config.settings().addonsDir(), lock, name, Installer.externalFolders(config)));
        } finally {
          lock.write();
        }
      } else if (!config.external().containsKey(name)) {
        out.printf("%s was not installed%n", name);
      }
    }
    if (!edited.equals(read(config))) {
      ConfigEditor.write(config.file(), edited);
    }
    Reports.print(spec, new Installer.Report(outcomes, Map.of()));
    names.stream()
        .filter(config::has)
        .forEach(name ->
            out.printf(
                "removed %s from game %s in %s%s%n",
                name,
                config.name(),
                config.file(),
                config.external().containsKey(name) ? "; its folders stay in AddOns" : ""));
    return 0;
  }

  /**
   * The text of wowfam.toml without the named entries, checked by parsing it: every other addon,
   * the settings and the hooks must come out exactly as before.
   */
  private static String withoutEntries(ConfigLoader loader, Config config, Iterable<String> names) {
    var original = read(config);
    var text = original;
    for (var name : names) {
      if (!config.has(name)) {
        continue;
      }
      text =
          ConfigEditor.removeAddon(text, config.name(), name)
              .orElseThrow(() ->
                  new ConfigException(
                      "cannot find the [games."
                          + config.name()
                          + ".addons."
                          + name
                          + "] table in "
                          + config.file()
                          + "; take the entry out by hand"));
    }
    var before = loader.parse(config.file(), original);
    var after = loader.parse(config.file(), text);
    var expected = new LinkedHashMap<>(before.games());
    var game = before.game(config.name());
    var addons = new LinkedHashMap<>(game.addons());
    var external = new LinkedHashMap<>(game.external());
    names.forEach(addons::remove);
    names.forEach(external::remove);
    expected.put(
        game.name(),
        new Config(game.file(), game.name(), game.settings(), game.hooks(), addons, external));
    if (!after.equals(new Workspace(before.file(), before.defaultGame(), expected))) {
      throw new ConfigException(
          "cannot remove "
              + String.join(", ", names)
              + " from "
              + config.file()
              + " without changing other entries; take it out by hand");
    }
    return text;
  }

  private static String read(Config config) {
    try {
      return Files.readString(config.file());
    } catch (IOException e) {
      throw new ConfigException("cannot read " + config.file() + ": " + e.getMessage(), e);
    }
  }
}
