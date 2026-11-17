package io.github.tinnet.wowfam.cli;

import static java.util.stream.Collectors.joining;

import io.github.tinnet.wowfam.config.Registry;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "registry",
    mixinStandardHelpOptions = true,
    description = {
      "The well-known addons, which wowfam add takes by name (wowfam add questie).",
      "Without a subcommand, lists them."
    },
    subcommands = RegistryCommand.RegistryListCommand.class)
final class RegistryCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    print(spec);
    return 0;
  }

  /** Every entry with what {@code add} starts from; needs no wowfam.toml. */
  static void print(CommandSpec spec) {
    var table = new Table("NAME", "SOURCE", "OPTIONS");
    for (var entry : Registry.all()) {
      table.row(
          entry.name(),
          entry.source().toString(),
          Registry.OPTIONS.stream()
              .filter(entry.options()::containsKey)
              .map(key -> key + "=" + entry.options().get(key))
              .collect(joining(" ")));
    }
    table.print(spec.commandLine().getOut());
  }

  @Command(
      name = "ls",
      aliases = "list",
      mixinStandardHelpOptions = true,
      description = "List the well-known addons, their sources and the options add starts from.")
  static final class RegistryListCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
      print(spec);
      return 0;
    }
  }
}
