package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.install.Installer;
import picocli.CommandLine.Model.CommandSpec;

final class Reports {

  private Reports() {}

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
