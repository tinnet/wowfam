package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.install.Installer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "install",
    aliases = "i",
    mixinStandardHelpOptions = true,
    description =
        "Install addons from wowfam.toml (all, or the named ones and their dependencies). "
            + "Keeps the version recorded in wowfam.lock when it still matches.")
final class InstallCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Parameters(arity = "0..*", paramLabel = "ADDON", description = "Addon names from wowfam.toml.")
  List<String> addons = new ArrayList<>();

  @Option(
      names = {"-f", "--force"},
      description = "Reinstall even if already installed.")
  boolean force;

  @Override
  public Integer call() {
    return Reports.print(
        spec,
        new Installer(root.backends(), spec.commandLine().getOut()::println)
            .run(root.loadConfig(), addons, Installer.Mode.INSTALL, force));
  }
}
