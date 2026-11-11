package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.lock.LockEntry;
import io.github.tinnet.wowfam.lock.LockFile;
import io.github.tinnet.wowfam.util.Version;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "outdated",
    mixinStandardHelpOptions = true,
    description = "Show addons with a newer release matching wowfam.toml. Exits 0 either way.")
final class OutdatedCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    var config = root.loadConfig();
    var lock = LockFile.read(config.lockFile());
    var backends = root.backends();
    var table = new Table("ADDON", "INSTALLED", "LATEST");
    int outdated = 0;
    for (var addon : config.addons().values()) {
      var backend = backends.get(addon.source().backend());
      var newest = ReleaseSelector.newest(backend, addon, backend.releases(addon));
      if (newest.isEmpty()) {
        continue;
      }
      var installed = lock.get(addon.name()).map(LockEntry::version);
      var isOutdated =
          installed
              .flatMap(Version::parse)
              .map(v -> v.compareTo(newest.orElseThrow().version()) < 0)
              .orElse(true);
      if (isOutdated) {
        outdated++;
        table.row(addon.name(), installed.orElse("-"), newest.orElseThrow().version().toString());
      }
    }
    var out = spec.commandLine().getOut();
    if (outdated == 0) {
      out.println("all addons are up to date");
    } else {
      table.print(out);
    }
    return 0;
  }
}
