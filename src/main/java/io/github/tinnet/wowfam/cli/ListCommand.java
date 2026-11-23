package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.lock.LockEntry;
import io.github.tinnet.wowfam.lock.LockFile;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "list",
    aliases = "ls",
    mixinStandardHelpOptions = true,
    description = "List the games and addons in wowfam.toml and what is installed.")
final class ListCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    var workspace = root.loadWorkspace();
    var games = root.games(workspace);
    for (var config : games) {
      if (!config.name().equals(games.getFirst().name())) {
        out.println();
      }
      var lock = LockFile.read(config.lockFile(), config.name());
      out.println(
          config.name()
              + ": "
              + config.settings().game().map(Object::toString).orElse("unknown client")
              + ", "
              + config.settings().addonsDir());
      var table = new Table("ADDON", "REQUESTED", "INSTALLED", "SOURCE");
      for (var addon : config.addons().values()) {
        var installed = lock.get(addon.name()).map(LockEntry::version).orElse("-");
        table.row(addon.name(), addon.version(), installed, addon.source().toString());
      }
      var addonsDir = config.settings().addonsDir();
      for (var external : config.external().values()) {
        var missing =
            external.folders().stream()
                .filter(f -> !Files.isDirectory(addonsDir.resolve(f)))
                .toList();
        var present =
            missing.isEmpty()
                ? "present"
                : missing.size() == external.folders().size()
                    ? "missing"
                    : "missing " + String.join(", ", missing);
        table.row(external.name(), "-", present, external.describe());
      }
      table.print(out);
      List<String> unclaimed;
      try {
        unclaimed = AddOnsFolder.unclaimed(config, lock);
      } catch (ConfigException e) {
        // Only a hint: the table above is what list is for.
        spec.commandLine().getErr().println("warning: " + e.getMessage());
        unclaimed = List.of();
      }
      if (!unclaimed.isEmpty()) {
        out.printf(
            "not in wowfam.toml: %s%n(wowfam external --all%s records them as kept by something"
                + " else)%n",
            String.join(", ", unclaimed),
            workspace.games().size() > 1 ? " -g " + config.name() : "");
      }
    }
    return 0;
  }
}
