package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Workspace;
import io.github.tinnet.wowfam.install.InstallException;
import io.github.tinnet.wowfam.lock.LockFileException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
    name = "wowfam",
    mixinStandardHelpOptions = true,
    versionProvider = WowfamCommand.VersionProvider.class,
    description = {
      "Installs and updates World of Warcraft addons listed in wowfam.toml.",
      "Start with wowfam init, which finds your games and writes the config."
    },
    subcommands = {
      AddCommand.class,
      CommandLine.HelpCommand.class,
      InitCommand.class,
      InstallCommand.class,
      ListCommand.class,
      OutdatedCommand.class,
      RegistryCommand.class,
      RemoveCommand.class,
      UpdateCommand.class
    })
public final class WowfamCommand implements Runnable {

  @Option(
      names = {"-C", "--config"},
      scope = ScopeType.INHERIT,
      description =
          "Path to wowfam.toml (default: $WOWFAM_CONFIG, else wowfam/wowfam.toml in the user's"
              + " config folder: $XDG_CONFIG_HOME or ~/.config, %APPDATA% on Windows).",
      paramLabel = "FILE")
  @Nullable Path config;

  @Option(
      names = {"-g", "--game"},
      scope = ScopeType.INHERIT,
      description =
          "The game to work on, by its name in wowfam.toml (default: every game; add and remove"
              + " use settings.default_game).",
      paramLabel = "GAME")
  @Nullable String game;

  @Option(
      names = "--addons-dir",
      scope = ScopeType.INHERIT,
      description = "Install into this folder instead of the game's AddOns.",
      paramLabel = "DIR")
  @Nullable Path addonsDir;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  final Map<String, String> env;

  /** The operating system's name, as in {@code os.name}: where to look for games and config. */
  final String os;

  /** The backends, given the cache directory of the config they serve. */
  private final Function<Path, Backends> backends;

  /** Where {@code init} reads answers from; empty when nobody is there to answer. */
  final Optional<BufferedReader> answers;

  public WowfamCommand(
      Map<String, String> env,
      String os,
      Function<Path, Backends> backends,
      Optional<BufferedReader> answers) {
    this.env = env;
    this.os = os;
    this.backends = backends;
    this.answers = answers;
  }

  // System.console() can still be null: JDK 25 went back to java.base's console, which has none
  // without a terminal, and so does a native image.
  @SuppressWarnings("SystemConsoleNull")
  public static CommandLine newCommandLine() {
    var env = System.getenv();
    // Questions only when someone can answer them: a terminal, not a pipe or a scheduled job.
    var console = System.console();
    var answers =
        console != null && console.isTerminal()
            ? Optional.of(new BufferedReader(new InputStreamReader(System.in, console.charset())))
            : Optional.<BufferedReader>empty();
    return newCommandLine(env, cacheDir -> defaultBackends(env, cacheDir), answers);
  }

  public static CommandLine newCommandLine(
      Map<String, String> env, Function<Path, Backends> backends) {
    return newCommandLine(env, backends, Optional.empty());
  }

  public static CommandLine newCommandLine(
      Map<String, String> env,
      Function<Path, Backends> backends,
      Optional<BufferedReader> answers) {
    return newCommandLine(env, System.getProperty("os.name", ""), backends, answers);
  }

  /**
   * The CLI as it runs on {@code os}. Tests give one so that what {@code init} finds doesn't depend
   * on the machine: on macOS it would also look in the real {@code /Applications}.
   */
  public static CommandLine newCommandLine(
      Map<String, String> env,
      String os,
      Function<Path, Backends> backends,
      Optional<BufferedReader> answers) {
    var cl = new CommandLine(new WowfamCommand(env, os, backends, answers));
    cl.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
      if (ex instanceof ConfigException
          || ex instanceof BackendException
          || ex instanceof InstallException
          || ex instanceof LockFileException) {
        commandLine
            .getErr()
            .println(commandLine.getColorScheme().errorText("error: " + ex.getMessage()));
        return 1;
      }
      throw ex;
    });
    return cl;
  }

  static Backends defaultBackends(Map<String, String> env, Path cacheDir) {
    var token =
        Optional.ofNullable(env.get("GITHUB_TOKEN"))
            .or(() -> Optional.ofNullable(env.get("GH_TOKEN")));
    var base =
        Optional.ofNullable(env.get("WOWFAM_GITHUB_API"))
            .map(URI::create)
            .orElse(GitHubApi.DEFAULT_BASE);
    return new Backends(
        List.of(new GitHubBackend(new GitHubApi(Http.client(env), base, token), cacheDir)));
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** The user's home: {@code $HOME} (so tests can move it), else Java's idea of it. */
  Path home() {
    return Optional.ofNullable(env.get("HOME"))
        .filter(h -> !h.isBlank())
        .map(Path::of)
        .orElseGet(() -> Path.of(System.getProperty("user.home")));
  }

  /** {@code --config}, else {@link #defaultConfigFile}. */
  Path configFile() {
    return config != null
        ? config.toAbsolutePath().normalize()
        : defaultConfigFile(env, home(), os);
  }

  /**
   * {@code $WOWFAM_CONFIG}, else {@code wowfam/wowfam.toml} in the user's config folder: {@code
   * %APPDATA%} on Windows, {@code $XDG_CONFIG_HOME} or {@code ~/.config} elsewhere (macOS too, like
   * most command-line tools).
   */
  static Path defaultConfigFile(Map<String, String> env, Path home, String os) {
    var explicit = env.get("WOWFAM_CONFIG");
    if (explicit != null && !explicit.isBlank()) {
      return Path.of(explicit).toAbsolutePath().normalize();
    }
    var appData = env.get("APPDATA");
    var xdg = env.get("XDG_CONFIG_HOME");
    Path dir;
    if (os.startsWith("Windows") && appData != null && !appData.isBlank()) {
      dir = Path.of(appData);
    } else if (xdg != null && !xdg.isBlank()) {
      dir = Path.of(xdg);
    } else {
      dir = home.resolve(".config");
    }
    return dir.resolve("wowfam").resolve(Config.FILE_NAME);
  }

  ConfigLoader loader() {
    return new ConfigLoader(env, home());
  }

  Workspace loadWorkspace() {
    return loader().load(configFile(), Optional.ofNullable(game), addonsDir);
  }

  /** The one game {@code add} and {@code remove} work on. */
  Config oneGame(Workspace workspace) {
    return workspace.one(Optional.ofNullable(game));
  }

  /** The games {@code install}, {@code update}, {@code outdated} and {@code list} work on. */
  List<Config> games(Workspace workspace) {
    return workspace.selected(Optional.ofNullable(game));
  }

  Backends backends(Config config) {
    return backends.apply(config.settings().cacheDir());
  }

  static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"wowfam " + io.github.tinnet.wowfam.BuildInfo.VERSION};
    }
  }
}
