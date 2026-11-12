package io.github.tinnet.wowfam.install;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs the shell commands of a hook ({@code sh -c}, or {@code cmd /c} on Windows) from the
 * directory holding {@code wowfam.toml}. Each command sees the variables of {@link #run} on top of
 * wowfam's own environment; its output is passed to the log line by line and its input is wowfam's,
 * so a command may prompt. The first command that exits non-zero fails the hook.
 */
final class HookRunner {

  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  private final Path workingDir;
  private final Consumer<String> log;

  HookRunner(Path workingDir, Consumer<String> log) {
    this.workingDir = workingDir;
    this.log = log;
  }

  /**
   * @param hook the hook's name, e.g. {@code pre_install}, for messages
   * @param addon the addon's name, for messages
   * @param env {@code WOWFAM_*} variables describing the installation
   */
  void run(String hook, String addon, List<String> commands, Map<String, String> env) {
    for (var command : commands) {
      log.accept("running " + hook + " hook for " + addon + ": " + command);
      var builder =
          new ProcessBuilder(
                  WINDOWS ? List.of("cmd.exe", "/c", command) : List.of("sh", "-c", command))
              .directory(workingDir.toFile())
              .redirectErrorStream(true)
              .redirectInput(ProcessBuilder.Redirect.INHERIT);
      builder.environment().putAll(env);
      int exit;
      try {
        var process = builder.start();
        try (var out =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
          for (String line; (line = out.readLine()) != null; ) {
            log.accept("  " + line);
          }
        }
        exit = process.waitFor();
      } catch (IOException e) {
        throw new InstallException(
            "cannot run " + hook + " hook for " + addon + " (" + command + "): " + e.getMessage(),
            e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InstallException(hook + " hook for " + addon + " was interrupted", e);
      }
      if (exit != 0) {
        throw new InstallException(
            hook + " hook for " + addon + " failed with exit code " + exit + ": " + command);
      }
    }
  }
}
