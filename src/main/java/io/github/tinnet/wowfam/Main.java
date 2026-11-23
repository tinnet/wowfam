package io.github.tinnet.wowfam;

import io.github.tinnet.wowfam.cli.WowfamCommand;
import picocli.CommandLine;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    var commandLine = WowfamCommand.newCommandLine();
    int status = commandLine.execute(args);
    // The writers flush on println alone; a print without a line end would be lost at exit.
    commandLine.getOut().flush();
    commandLine.getErr().flush();
    System.exit(status);
  }

  /** Entry point for tests: runs the CLI without exiting the JVM. */
  public static int run(CommandLine commandLine, String... args) {
    return commandLine.execute(args);
  }
}
