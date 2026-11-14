package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.install.Installer;
import java.util.List;
import picocli.CommandLine.Model.CommandSpec;

final class Reports {

  private Reports() {}

  /**
   * Installs or updates {@code names} (or everything) in each game the command works on, printing
   * each game's report under its name when there are several.
   */
  static int runGames(
      WowfamCommand root,
      CommandSpec spec,
      List<String> names,
      Installer.Mode mode,
      boolean force,
      boolean hooks) {
    var workspace = root.loadWorkspace();
    var games = root.games(workspace);
    for (var name : names) {
      if (games.stream().noneMatch(g -> g.addons().containsKey(name))) {
        throw new ConfigException(
            "no addon named '"
                + name
                + "' in "
                + (games.size() == 1 ? "game " + games.getFirst().name() + " of " : "")
                + workspace.file());
      }
    }
    if (!names.isEmpty()) {
      games = games.stream().filter(g -> names.stream().anyMatch(g.addons()::containsKey)).toList();
    }
    var out = spec.commandLine().getOut();
    int status = 0;
    for (var game : games) {
      if (games.size() > 1) {
        out.println(game.name() + ":");
      }
      var own = names.stream().filter(game.addons()::containsKey).toList();
      status |=
          print(
              spec,
              new Installer(root.backends(game), out::println, hooks).run(game, own, mode, force));
    }
    return status;
  }

  static int print(CommandSpec spec, Installer.Report report) {
    var out = spec.commandLine().getOut();
    for (var o : report.outcomes()) {
      switch (o.status()) {
        case INSTALLED ->
            out.printf("✓ %s %s  (%s)%n", o.addon(), o.version(), String.join(", ", o.folders()));
        case UPDATED, DOWNGRADED ->
            out.printf(
                "%s %s %s -> %s  (%s)%n",
                o.status() == Installer.Status.UPDATED ? "↑" : "↓",
                o.addon(),
                o.previousVersion().orElse("?"),
                o.version(),
                String.join(", ", o.folders()));
        case REMOVED ->
            out.printf(
                "- %s %s  (%s)%n",
                o.addon(),
                o.version(),
                o.folders().isEmpty()
                    ? "its folders belong to another addon now"
                    : String.join(", ", o.folders()));
        case UP_TO_DATE -> {}
      }
    }
    var err = spec.commandLine().getErr();
    report
        .missingDependencies()
        .forEach((folder, deps) ->
            err.printf(
                "warning: %s requires %s, which is not installed%n",
                folder, String.join(", ", deps)));
    return 0;
  }
}
