package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.lock.LockEntry;
import io.github.tinnet.wowfam.lock.LockFile;
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
    var games = root.games(root.loadWorkspace());
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
      table.print(out);
    }
    return 0;
  }
}
