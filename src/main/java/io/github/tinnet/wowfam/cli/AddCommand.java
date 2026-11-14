package io.github.tinnet.wowfam.cli;

import static java.util.stream.Collectors.toUnmodifiableSet;

import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigEditor;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Source;
import io.github.tinnet.wowfam.install.Installer;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "add",
    description = {
      "Add an addon to wowfam.toml and install it.",
      "Works out tag_prefix and asset from the source's releases, checks that they find a "
          + "release, appends the entry (comments and layout of the file are kept) and prints it."
    })
final class AddCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  // Not mixinStandardHelpOptions: that would claim --version, which here is the addon's.
  @Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "Show this help message and exit.")
  boolean help;

  @Parameters(
      index = "0",
      paramLabel = "SOURCE",
      description = "github:owner/repo, owner/repo, or a https://github.com/owner/repo URL.")
  String source = "";

  @Option(
      names = "--name",
      paramLabel = "NAME",
      description = "Name of the entry (default: the repository name in kebab-case).")
  @Nullable String name;

  @Option(
      names = "--version",
      paramLabel = "VERSION",
      description = "latest (default), an exact version, or a prefix such as 3.8.")
  String version = AddonSpec.LATEST;

  @Option(
      names = "--tag-prefix",
      paramLabel = "PREFIX",
      description = "Only tags starting with this, e.g. spoken/ (default: worked out).")
  @Nullable String tagPrefix;

  @Option(
      names = "--asset",
      paramLabel = "GLOB",
      description = "Glob for the zip to install (default: worked out).")
  @Nullable String asset;

  @Option(
      names = "--flavor",
      paramLabel = "FLAVOR",
      description = "Game flavor, for addons whose releases carry a release.json.")
  @Nullable String flavor;

  @Option(names = "--nolib", description = "With --flavor: take the -nolib zip.")
  boolean nolib;

  @Option(names = "--prerelease", description = "Consider prereleases.")
  boolean prerelease;

  @Option(
      names = {"-n", "--dry-run"},
      description = "Print the entry without changing wowfam.toml or installing.")
  boolean dryRun;

  @Option(names = "--no-install", description = "Only add the entry to wowfam.toml.")
  boolean noInstall;

  @Option(
      names = "--no-hooks",
      description = "Skip the pre_install and post_install hooks of wowfam.toml.")
  boolean noHooks;

  @Override
  public Integer call() {
    var config = root.loadConfig();
    var src = Source.fromInput(source);
    var addonName = name != null ? name : defaultName(src);
    if (!ConfigEditor.NAME.matcher(addonName).matches()) {
      throw new ConfigException(
          "'" + addonName + "' cannot be an addon name: use letters, digits, - and _");
    }
    var taken = config.addons().get(addonName);
    if (taken != null) {
      throw new ConfigException(
          taken.source().sameAs(src)
              ? src + " is already in " + config.file() + " as addons." + addonName
              : "addons."
                  + addonName
                  + " is already in "
                  + config.file()
                  + "; pick another --name");
    }
    var options = new LinkedHashMap<String, String>();
    putIfSet(options, "tag_prefix", tagPrefix);
    putIfSet(options, "asset", asset);
    putIfSet(options, "flavor", flavor);
    if (nolib) {
      options.put("nolib", "true");
    }
    if (prerelease) {
      options.put("prerelease", "true");
    }
    // Not written to wowfam.toml: loading the file sets it from the game folder every time.
    var game = config.settings().game().filter(g -> flavor == null);
    game.ifPresent(g -> options.put("interface", Integer.toString(g.interfaceNumber())));

    var backends = root.backends(config);
    var backend = backends.get(src.backend());
    var addon = backend.discover(new AddonSpec(addonName, src, version, List.of(), options));
    for (var existing : config.addons().values()) {
      if (existing.source().sameAs(addon.source())
          && existing.option("tag_prefix").equals(addon.option("tag_prefix"))
          && existing.option("flavor").equals(addon.option("flavor"))
          // Without an asset the existing entry takes any zip; with one, a different asset is a
          // different addon from the same releases (Polestar and Polestar_Recorder).
          && (existing.option("asset").isEmpty()
              || existing.option("asset").equals(addon.option("asset")))) {
        throw new ConfigException(
            src + " is already in " + config.file() + " as addons." + existing.name());
      }
    }
    var release =
        ReleaseSelector.newest(backend, addon, backend.releases(addon))
            .orElseThrow(() -> new ConfigException(ReleaseSelector.noReleaseMessage(addon)));
    try {
      release.asset();
    } catch (BackendException e) {
      throw new BackendException(src + ": " + e.getMessage(), e);
    }

    var written = new LinkedHashMap<>(addon.options());
    if (game.isPresent()) {
      written.remove("interface");
    }
    var table =
        ConfigEditor.addonTable(
            new AddonSpec(addon.name(), addon.source(), addon.version(), List.of(), written));
    var out = spec.commandLine().getOut();
    if (dryRun) {
      out.print(table);
      out.printf(
          "(dry run: would install %s from %s%s)%n",
          release.version(), release.tag(), game.map(g -> " for " + g).orElse(""));
      return 0;
    }
    var original = read(config.file());
    var edited = ConfigEditor.append(original, table);
    // Appending a table is only wrong when the file keeps its addons in a form a new [addons.x]
    // cannot join (an inline table); parse first so that never reaches the disk.
    if (!new ConfigLoader().parse(config.file(), edited).addons().containsKey(addonName)) {
      throw new ConfigException("cannot add addons." + addonName + " to " + config.file());
    }
    ConfigEditor.write(config.file(), edited);
    out.printf("added to %s:%n%s", config.file(), table);
    if (noInstall) {
      return 0;
    }

    var updated = root.loadConfig();
    var unmanaged = unmanagedFolders(updated);
    var lockedBefore = LockFile.read(updated.lockFile()).get(addonName);
    Installer.Report report;
    try {
      report =
          new Installer(backends, out::println, !noHooks)
              .run(updated, List.of(addonName), Installer.Mode.INSTALL, false);
    } catch (RuntimeException e) {
      // Keep wowfam.toml and the AddOns folder in step: an addon that did not get installed (a
      // failed download, a pre_install hook saying no) is taken out again. One that did, but whose
      // post_install hook failed, is locked and stays. A lock entry left over from an earlier
      // addon of the same name doesn't count: only one this run wrote does.
      if (LockFile.read(updated.lockFile()).get(addonName).equals(lockedBefore)) {
        ConfigEditor.write(config.file(), original);
        spec.commandLine().getErr().println("not installed, so not added to " + config.file());
      }
      throw e;
    }
    int status = Reports.print(spec, report);
    report.outcomes().stream()
        .flatMap(o -> o.folders().stream())
        .filter(unmanaged::contains)
        .forEach(f -> out.printf("%s was already in AddOns; wowfam manages it now%n", f));
    return status;
  }

  /**
   * The repository name in kebab-case: {@code BetterVendorPrice} -> {@code better-vendor-price}.
   */
  static String defaultName(Source source) {
    return source
        .shortName()
        .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("[^A-Za-z0-9]+", "-")
        .replaceAll("^-+|-+$", "")
        .toLowerCase(Locale.ROOT);
  }

  private static void putIfSet(Map<String, String> options, String key, @Nullable String value) {
    if (value != null && !value.isBlank()) {
      options.put(key, value);
    }
  }

  /** Folders in AddOns that no entry of the lock owns. */
  private static Set<String> unmanagedFolders(Config config) {
    var lock = LockFile.read(config.lockFile());
    var dir = config.settings().addonsDir();
    if (!Files.isDirectory(dir)) {
      return Set.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(f -> lock.ownerOf(f).isEmpty())
          .collect(toUnmodifiableSet());
    } catch (IOException e) {
      throw new ConfigException("cannot list " + dir + ": " + e.getMessage(), e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }
}
