package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.install.InstallException;
import io.github.tinnet.wowfam.lock.LockFileException;
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
    description = "Installs and updates World of Warcraft addons listed in wowfam.toml.",
    subcommands = {
      AddCommand.class,
      CommandLine.HelpCommand.class,
      InstallCommand.class,
      ListCommand.class,
      OutdatedCommand.class,
      RemoveCommand.class,
      UpdateCommand.class
    })
public final class WowfamCommand implements Runnable {

  @Option(
      names = {"-C", "--config"},
      scope = ScopeType.INHERIT,
      description = "Path to wowfam.toml (default: ./wowfam.toml).",
      paramLabel = "FILE")
  @Nullable Path config;

  @Option(
      names = "--addons-dir",
      scope = ScopeType.INHERIT,
      description = "Override settings.addons_dir.",
      paramLabel = "DIR")
  @Nullable Path addonsDir;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  private final Map<String, String> env;

  /** The backends, given the cache directory of the config they serve. */
  private final Function<Path, Backends> backends;

  public WowfamCommand(Map<String, String> env, Function<Path, Backends> backends) {
    this.env = env;
    this.backends = backends;
  }

  public static CommandLine newCommandLine() {
    var env = System.getenv();
    return newCommandLine(env, cacheDir -> defaultBackends(env, cacheDir));
  }

  public static CommandLine newCommandLine(
      Map<String, String> env, Function<Path, Backends> backends) {
    var cl = new CommandLine(new WowfamCommand(env, backends));
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

  Config loadConfig() {
    var file = config != null ? config : Path.of(Config.FILE_NAME);
    return new ConfigLoader(env, Path.of(System.getProperty("user.home"))).load(file, addonsDir);
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
