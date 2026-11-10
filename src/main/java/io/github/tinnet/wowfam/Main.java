package io.github.tinnet.wowfam;

import io.github.tinnet.wowfam.cli.WowfamCommand;
import picocli.CommandLine;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.exit(WowfamCommand.newCommandLine().execute(args));
  }

  /** Entry point for tests: runs the CLI without exiting the JVM. */
  public static int run(CommandLine commandLine, String... args) {
    return commandLine.execute(args);
  }
}
