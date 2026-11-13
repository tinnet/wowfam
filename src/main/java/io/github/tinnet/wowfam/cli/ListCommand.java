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
    description = "List the addons in wowfam.toml and what is installed.")
final class ListCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    var config = root.loadConfig();
    var lock = LockFile.read(config.lockFile());
    var table = new Table("ADDON", "REQUESTED", "INSTALLED", "SOURCE");
    for (var spec : config.addons().values()) {
      var installed = lock.get(spec.name()).map(LockEntry::version).orElse("-");
      table.row(spec.name(), spec.version(), installed, spec.source().toString());
    }
    var out = spec.commandLine().getOut();
    config.settings().game().ifPresent(g -> out.println("game: " + g));
    table.print(out);
    return 0;
  }
}
