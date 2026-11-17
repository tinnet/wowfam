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
    })
final class RegistryCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  /** Every entry with what {@code add} starts from; needs no wowfam.toml. */
  @Override
  public Integer call() {
    var table = new Table("NAME", "SOURCE", "OPTIONS");
    for (var entry : Registry.all()) {
      table.row(
          entry.name(),
          entry.source().toString(),
          entry.options().entrySet().stream()
              .map(o -> o.getKey() + "=" + o.getValue())
              .collect(joining(" ")));
    }
    table.print(spec.commandLine().getOut());
    return 0;
  }

  @Command(
      name = "ls",
      aliases = "list",
      mixinStandardHelpOptions = true,
      description = "List the well-known addons, their sources and the options add starts from.")
  int ls() {
    return call();
  }
}
