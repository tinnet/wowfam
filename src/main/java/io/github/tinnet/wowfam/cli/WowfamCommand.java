package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Settings;
import io.github.tinnet.wowfam.install.InstallException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
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
      InstallCommand.class,
      UpdateCommand.class,
      ListCommand.class,
      OutdatedCommand.class,
      CommandLine.HelpCommand.class
    })
public final class WowfamCommand implements Runnable {

  @Option(
      names = {"-C", "--config"},
      scope = ScopeType.INHERIT,
      description = "Path to wowfam.toml (default: ./wowfam.toml).",
      paramLabel = "FILE")
  Path config;

  @Option(
      names = "--addons-dir",
      scope = ScopeType.INHERIT,
      description = "Override settings.addons_dir.",
      paramLabel = "DIR")
  Path addonsDir;

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  private final Map<String, String> env;
  private final Supplier<Backends> backends;

  public WowfamCommand(Map<String, String> env, Supplier<Backends> backends) {
    this.env = env;
    this.backends = backends;
  }

  public static CommandLine newCommandLine() {
    var env = System.getenv();
    return newCommandLine(env, () -> defaultBackends(env));
  }

  public static CommandLine newCommandLine(Map<String, String> env, Supplier<Backends> backends) {
    var cl = new CommandLine(new WowfamCommand(env, backends));
    cl.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
      if (ex instanceof ConfigException
          || ex instanceof BackendException
          || ex instanceof InstallException) {
        commandLine
            .getErr()
            .println(commandLine.getColorScheme().errorText("error: " + ex.getMessage()));
        return 1;
      }
      throw ex;
    });
    return cl;
  }

  static Backends defaultBackends(Map<String, String> env) {
    var token =
        Optional.ofNullable(env.get("GITHUB_TOKEN"))
            .or(() -> Optional.ofNullable(env.get("GH_TOKEN")));
    var base =
        Optional.ofNullable(env.get("WOWFAM_GITHUB_API"))
            .map(URI::create)
            .orElse(GitHubApi.DEFAULT_BASE);
    return new Backends(List.of(new GitHubBackend(new GitHubApi(Http.client(env), base, token))));
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  Config loadConfig() {
    var file = config != null ? config : Path.of(Config.FILE_NAME);
    var loaded = new ConfigLoader(env, Path.of(System.getProperty("user.home"))).load(file);
    if (addonsDir == null) {
      return loaded;
    }
    return new Config(
        loaded.file(),
        new Settings(addonsDir.toAbsolutePath().normalize(), loaded.settings().cacheDir()),
        loaded.addons());
  }

  Backends backends() {
    return backends.get();
  }

  static final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"wowfam " + io.github.tinnet.wowfam.BuildInfo.VERSION};
    }
  }
}
