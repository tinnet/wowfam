package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.install.Installer;
import io.github.tinnet.wowfam.install.Progress;
import java.util.List;
import java.util.Optional;
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
    var live = live(root, spec);
    try {
      Progress progress = live.isPresent() ? live.orElseThrow() : Progress.lines(out::println);
      for (var game : games) {
        if (games.size() > 1) {
          progress.log(game.name() + ":");
        }
        var own = names.stream().filter(game.addons()::containsKey).toList();
        var report =
            new Installer(root.backends(game), progress, hooks).run(game, own, mode, force);
        status |= print(spec, report, live.isEmpty());
      }
    } finally {
      live.ifPresent(LiveProgress::close);
    }
    return status;
  }

  /** Progress for a run that prints to {@code spec}'s output: live on a terminal, else lines. */
  static Optional<LiveProgress> live(WowfamCommand root, CommandSpec spec) {
    return LiveProgress.open(root.env, o -> line(spec, o));
  }

  static int print(CommandSpec spec, Installer.Report report) {
    return print(spec, report, true);
  }

  /**
   * Prints the report: each addon's outcome (unless live progress showed them already) and the
   * dependencies missing from AddOns.
   */
  static int print(CommandSpec spec, Installer.Report report, boolean outcomes) {
    var out = spec.commandLine().getOut();
    if (outcomes) {
      report.outcomes().forEach(o -> line(spec, o).ifPresent(out::println));
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

  /** The line an addon's outcome leaves in the output, coloured if the output takes colour. */
  static Optional<String> line(CommandSpec spec, Installer.Outcome o) {
    var ansi = spec.commandLine().getColorScheme().ansi();
    var folders = String.join(", ", o.folders());
    return Optional.ofNullable(
            switch (o.status()) {
              case INSTALLED ->
                  "@|green ✓|@ @|bold %s|@ %s  @|faint (%s)|@"
                      .formatted(o.addon(), o.version(), folders);
              case UPDATED, DOWNGRADED ->
                  "%s @|bold %s|@ %s -> %s  @|faint (%s)|@"
                      .formatted(
                          o.status() == Installer.Status.UPDATED ? "@|cyan ↑|@" : "@|yellow ↓|@",
                          o.addon(),
                          o.previousVersion().orElse("?"),
                          o.version(),
                          folders);
              case REMOVED ->
                  "@|faint -|@ @|bold %s|@ %s  @|faint (%s)|@"
                      .formatted(
                          o.addon(),
                          o.version(),
                          o.folders().isEmpty()
                              ? "its folders belong to another addon now"
                              : folders);
              case UP_TO_DATE -> null;
            })
        .map(ansi::string);
  }
}
