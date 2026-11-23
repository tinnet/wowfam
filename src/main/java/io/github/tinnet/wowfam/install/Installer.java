package io.github.tinnet.wowfam.install;

import io.github.tinnet.wowfam.backend.Asset;
import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.Hooks;
import io.github.tinnet.wowfam.lock.LockEntry;
import io.github.tinnet.wowfam.lock.LockFile;
import io.github.tinnet.wowfam.util.Checksum;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Installs and updates addons: resolve a release with the addon's backend, download its archive to
 * the cache, verify it, unpack it into the AddOns directory and record it in the lock file.
 */
public final class Installer {

  public enum Mode {
    /** Install what is missing; keep the locked release when it still satisfies the config. */
    INSTALL,
    /** Move every addon to the newest release that satisfies the config. */
    UPDATE
  }

  public enum Status {
    INSTALLED,
    UPDATED,
    DOWNGRADED,
    UP_TO_DATE,
    /** Uninstalled: its folders are deleted and its lock entry is gone. */
    REMOVED
  }

  public record Outcome(
      String addon,
      Status status,
      Optional<String> previousVersion,
      String version,
      List<String> folders) {}

  /**
   * The result of a run.
   *
   * @param missingDependencies addon folder -> required folders (from its .toc) not present in the
   *     AddOns directory
   * @param missingExternal external addon -> its folders not present in the AddOns directory
   */
  public record Report(
      List<Outcome> outcomes,
      Map<String, List<String>> missingDependencies,
      Map<String, List<String>> missingExternal) {

    public Report(List<Outcome> outcomes, Map<String, List<String>> missingDependencies) {
      this(outcomes, missingDependencies, Map.of());
    }
  }

  private final Backends backends;
  private final Progress progress;
  private final boolean runHooks;

  public Installer(Backends backends, Consumer<String> log) {
    this(backends, Progress.lines(log), true);
  }

  public Installer(Backends backends, Consumer<String> log, boolean runHooks) {
    this(backends, Progress.lines(log), runHooks);
  }

  /**
   * @param progress where the run reports what it does
   * @param runHooks whether to run the {@code pre_install} and {@code post_install} hooks of the
   *     config
   */
  public Installer(Backends backends, Progress progress, boolean runHooks) {
    this.backends = backends;
    this.progress = progress;
    this.runHooks = runHooks;
  }

  public Report run(Config config, List<String> names, Mode mode, boolean force) {
    var settings = config.settings();
    var lock = LockFile.read(config.lockFile(), config.name());
    var outcomes = new ArrayList<Outcome>();
    var gameDir = settings.gameDir();
    if (gameDir.isPresent() && !Files.isDirectory(gameDir.orElseThrow())) {
      throw new InstallException(
          "the folder of game "
              + config.name()
              + " is not there: "
              + gameDir.orElseThrow()
              + " (fix games."
              + config.name()
              + ".dir in "
              + config.file()
              + ")");
    }
    try {
      Files.createDirectories(settings.addonsDir());
    } catch (IOException e) {
      throw new InstallException(
          "cannot create " + settings.addonsDir() + ": " + e.getMessage(), e);
    }
    var order = config.installOrder(names);
    var externals = config.externalDependencies(names);
    progress.start(order.size());
    for (var spec : order) {
      try {
        progress.step(spec.name(), "resolving");
        var outcome = installOne(config, spec, lock, mode, force);
        outcomes.add(outcome);
        progress.finished(outcome);
      } finally {
        // A failing post_install hook leaves the addon installed; the lock must say so.
        lock.write();
      }
    }
    if (names.isEmpty()) {
      // A run over the whole file makes AddOns match it: an addon that was renamed or taken out
      // of wowfam.toml by hand is uninstalled.
      for (var name : List.copyOf(lock.entries().keySet())) {
        if (config.external().containsKey(name)) {
          // Handed over to another manager: every folder wowfam installed stays, listed or not,
          // since the other manager may keep folders the external entry doesn't name.
          progress.log("handing " + name + " over: it is external now, so its folders stay");
          lock.remove(name);
          lock.write();
        } else if (!config.addons().containsKey(name)) {
          progress.log("uninstalling " + name + ", which is no longer in game " + config.name());
          try {
            var outcome = uninstall(settings.addonsDir(), lock, name, externalFolders(config));
            outcomes.add(outcome);
            progress.finished(outcome);
          } finally {
            lock.write();
          }
        }
      }
    }
    var missingExternal = new LinkedHashMap<String, List<String>>();
    for (var external : externals) {
      var absent =
          external.folders().stream()
              .filter(f -> !Files.isDirectory(settings.addonsDir().resolve(f)))
              .toList();
      if (!absent.isEmpty()) {
        missingExternal.put(external.name(), absent);
      }
    }
    return new Report(outcomes, missingDependencies(settings.addonsDir(), lock), missingExternal);
  }

  /** The folders the external entries of {@code config} list, ignoring case. */
  public static Set<String> externalFolders(Config config) {
    var folders = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    config.external().values().forEach(e -> folders.addAll(e.folders()));
    return folders;
  }

  /**
   * Deletes the folders the lock records for {@code addon} and drops its entry; the caller writes
   * the lock. A folder another entry also records stays: it belongs to that addon now. So does one
   * in {@code keep} (the folders of external entries).
   */
  public static Outcome uninstall(Path addonsDir, LockFile lock, String addon, Set<String> keep) {
    var entry =
        lock.get(addon)
            .orElseThrow(() -> new IllegalArgumentException(addon + " is not in the lock"));
    var deleted = new ArrayList<String>();
    for (var folder : entry.folders()) {
      // A link is the user's (an addon being developed, linked in where the release was): only
      // the lock entry goes.
      if (keep.contains(folder)
          || Files.isSymbolicLink(addonsDir.resolve(folder))
          || lock.owners(folder).stream().anyMatch(o -> !o.equals(addon))) {
        continue;
      }
      try {
        deleteRecursively(addonsDir.resolve(folder));
      } catch (IOException e) {
        throw new InstallException(
            "cannot delete " + addonsDir.resolve(folder) + ": " + e.getMessage(), e);
      }
      deleted.add(folder);
    }
    lock.remove(addon);
    return new Outcome(
        addon, Status.REMOVED, Optional.of(entry.version()), entry.version(), deleted);
  }

  private Outcome installOne(
      Config config, AddonSpec spec, LockFile lock, Mode mode, boolean force) {
    var addonsDir = config.settings().addonsDir();
    // An entry from another source (the addon moved to a fork) is a different addon: install anew,
    // but still clear out the folders it had.
    var lockedAny = lock.get(spec.name());
    var locked = lockedAny.filter(e -> e.source().equals(spec.source().toString()));
    var backend = backends.get(spec.source().backend());
    var releases = backend.releases(spec);
    var lockedTag = mode == Mode.INSTALL ? locked.map(LockEntry::tag) : Optional.<String>empty();
    boolean present =
        locked.isPresent()
            && locked.orElseThrow().folders().stream()
                .allMatch(f -> Files.isDirectory(addonsDir.resolve(f)));
    var picked = ReleaseSelector.preferLocked(backend, spec, releases, lockedTag);
    if (picked.isEmpty()
        && present
        && ReleaseSelector.gameOf(spec).isPresent()
        && releases.stream().anyMatch(r -> r.version().satisfies(spec.version()))) {
      // Releases of the wanted version exist, just none for this game any more (it moved to a new
      // expansion the addon hasn't caught up with): keep what is installed rather than fail. A
      // version nothing satisfies is the user's mistake and stays an error.
      var kept = locked.orElseThrow();
      progress.log(
          "no release of "
              + spec.name()
              + " is for "
              + ReleaseSelector.gameOf(spec).orElseThrow()
              + "; keeping "
              + kept.version());
      return new Outcome(
          spec.name(),
          Status.UP_TO_DATE,
          Optional.of(kept.version()),
          kept.version(),
          kept.folders());
    }
    var release =
        picked.orElseThrow(() ->
            new InstallException(
                ReleaseSelector.noReleaseMessage(spec)
                    + (releases.isEmpty()
                        ? "; none found at all, check tag_prefix and asset"
                        : "")));

    // The same release is only up to date with the same zip: a changed asset, flavor or nolib
    // picks another zip of it.
    if (!force
        && present
        && locked.orElseThrow().tag().equals(release.tag())
        && release.assets().stream()
            .map(Asset::name)
            .toList()
            .equals(List.of(locked.orElseThrow().asset()))) {
      progress.status(spec.name(), spec.name() + " " + release.version() + " is up to date");
      return new Outcome(
          spec.name(),
          Status.UP_TO_DATE,
          Optional.of(locked.orElseThrow().version()),
          release.version().toString(),
          locked.orElseThrow().folders());
    }

    try {
      release.asset();
    } catch (BackendException e) {
      throw new BackendException("addons." + spec.name() + ": " + e.getMessage(), e);
    }
    var archive = fetch(config, spec, release);
    progress.status(
        spec.name(),
        "installing " + spec.name() + " " + release.version() + " from " + release.asset().name());
    var hooks = runHooks ? config.hooksFor(spec) : Hooks.NONE;
    var configDir = config.file().toAbsolutePath().getParent();
    var hookRunner =
        new HookRunner(configDir == null ? Path.of("").toAbsolutePath() : configDir, progress::log);
    var previous = locked.map(LockEntry::version);
    var folders =
        unpack(
            archive,
            addonsDir,
            spec.name(),
            lock,
            config,
            lockedAny.map(LockEntry::folders).orElseGet(List::of),
            (staging, unpacked) -> {
              if (!hooks.preInstall().isEmpty()) {
                hookRunner.run(
                    "pre_install",
                    spec.name(),
                    hooks.preInstall(),
                    hookEnv(
                        "pre_install",
                        config.name(),
                        spec,
                        release,
                        previous,
                        archive,
                        addonsDir,
                        staging,
                        unpacked));
              }
            });
    lock.put(
        spec.name(),
        new LockEntry(
            spec.source().toString(),
            release.version().toString(),
            release.tag(),
            release.asset().name(),
            release.asset().url().toString(),
            release.asset().checksum(),
            folders));
    if (!hooks.postInstall().isEmpty()) {
      hookRunner.run(
          "post_install",
          spec.name(),
          hooks.postInstall(),
          hookEnv(
              "post_install",
              config.name(),
              spec,
              release,
              previous,
              archive,
              addonsDir,
              addonsDir,
              folders));
    }
    var status =
        previous
            .flatMap(io.github.tinnet.wowfam.util.Version::parse)
            .map(p ->
                p.compareTo(release.version()) < 0
                    ? Status.UPDATED
                    : p.compareTo(release.version()) > 0 ? Status.DOWNGRADED : Status.INSTALLED)
            .orElse(Status.INSTALLED);
    return new Outcome(spec.name(), status, previous, release.version().toString(), folders);
  }

  /** What a hook command learns about the installation, as environment variables. */
  static Map<String, String> hookEnv(
      String hook,
      String game,
      AddonSpec spec,
      Release release,
      Optional<String> previousVersion,
      Path archive,
      Path addonsDir,
      Path dir,
      List<String> folders) {
    var env = new LinkedHashMap<String, String>();
    env.put("WOWFAM_HOOK", hook);
    env.put("WOWFAM_GAME", game);
    env.put("WOWFAM_ADDON", spec.name());
    env.put("WOWFAM_SOURCE", spec.source().toString());
    env.put("WOWFAM_VERSION", release.version().toString());
    env.put("WOWFAM_TAG", release.tag());
    env.put("WOWFAM_PREVIOUS_VERSION", previousVersion.orElse(""));
    env.put("WOWFAM_ARCHIVE", archive.toAbsolutePath().toString());
    env.put("WOWFAM_ADDONS_DIR", addonsDir.toAbsolutePath().toString());
    env.put("WOWFAM_DIR", dir.toAbsolutePath().toString());
    env.put("WOWFAM_FOLDERS", String.join("\n", folders));
    return env;
  }

  private Path fetch(Config config, AddonSpec spec, Release release) {
    var asset = release.asset();
    var target = config.settings().cacheDir().resolve(spec.name()).resolve(asset.name());
    if (Files.exists(target)
        && asset.checksum().filter(c -> c.equals(checksum(target, c.algorithm()))).isPresent()) {
      progress.status(spec.name(), "using cached " + asset.name());
      return target;
    }
    progress.status(
        spec.name(),
        "downloading "
            + asset.name()
            + (asset.size() > 0 ? " (" + humanSize(asset.size()) + ")" : ""));
    backends
        .get(spec.source().backend())
        .download(asset, target, done -> progress.bytes(done, asset.size()));
    if (asset.checksum().isPresent()) {
      var expected = asset.checksum().orElseThrow();
      var actual = checksum(target, expected.algorithm());
      if (!expected.equals(actual)) {
        throw new InstallException(
            "checksum mismatch for " + asset.name() + ": expected " + expected + ", got " + actual);
      }
    }
    return target;
  }

  /**
   * Extracts into a staging folder inside the AddOns directory (same file system, so moves are
   * cheap), calls {@code beforeSwap} with the staging folder and the addon folders in it, then
   * swaps the folders in and removes folders the previous release had but this one does not. If
   * {@code beforeSwap} throws, the AddOns directory is left as it was. A folder is refused if
   * another addon of {@code config} owns it, or an external entry lists it; a lock entry of an
   * addon no longer configured doesn't count.
   */
  private List<String> unpack(
      Path archive,
      Path addonsDir,
      String addon,
      LockFile lock,
      Config config,
      List<String> previousFolders,
      BiConsumer<Path, List<String>> beforeSwap) {
    var staging = addonsDir.resolve(".wowfam-staging-" + addon);
    try {
      deleteRecursively(staging);
      Files.createDirectories(staging);
      var folders = ZipExtractor.extract(archive, staging);
      for (var folder : folders) {
        var owner =
            lock.owners(folder).stream()
                .filter(o -> !o.equals(addon) && config.addons().containsKey(o))
                .findFirst();
        var target = addonsDir.resolve(folder);
        if (Files.isSymbolicLink(target)) {
          throw new InstallException(
              addon
                  + " wants to install folder "
                  + folder
                  + ", which is a link to "
                  + Files.readSymbolicLink(target)
                  + "; wowfam doesn't replace links (remove it to install the release)");
        }
        var external = config.externalOwner(folder);
        if (external.isPresent()) {
          var name = external.orElseThrow().name();
          throw new InstallException(
              addon
                  + " wants to install folder "
                  + folder
                  + ", which is in the external entry "
                  + name
                  + "; for wowfam to manage it, take "
                  + name
                  + " out of wowfam.toml or add this addon as "
                  + name);
        }
        if (owner.isPresent()) {
          throw new InstallException(
              addon
                  + " wants to install folder "
                  + folder
                  + ", which belongs to "
                  + owner.orElseThrow());
        }
      }
      beforeSwap.accept(staging, folders);
      var stale = new LinkedHashSet<>(previousFolders);
      stale.removeAll(folders);
      for (var folder : stale) {
        // A folder an external entry lists is someone else's now, even if this addon had it.
        if (config.externalOwner(folder).isEmpty()
            && !Files.isSymbolicLink(addonsDir.resolve(folder))) {
          deleteRecursively(addonsDir.resolve(folder));
        }
      }
      for (var folder : folders) {
        var target = addonsDir.resolve(folder);
        deleteRecursively(target);
        Files.move(staging.resolve(folder), target);
      }
      return folders;
    } catch (IOException e) {
      throw new InstallException(
          "cannot install " + addon + " into " + addonsDir + ": " + e.getMessage(), e);
    } finally {
      try {
        deleteRecursively(staging);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  /**
   * For every installed folder, the folders its .toc requires that are not in the AddOns directory.
   */
  static Map<String, List<String>> missingDependencies(Path addonsDir, LockFile lock) {
    var missing = new TreeMap<String, List<String>>();
    for (var entry : lock.entries().values()) {
      for (var folder : entry.folders()) {
        var dir = addonsDir.resolve(folder);
        if (!Files.isDirectory(dir)) {
          continue;
        }
        var absent =
            TocFile.readAll(dir).stream()
                .flatMap(toc -> toc.requiredDependencies().stream())
                .distinct()
                .filter(dep -> !Files.isDirectory(addonsDir.resolve(dep)))
                .toList();
        if (!absent.isEmpty()) {
          missing.put(folder, absent);
        }
      }
    }
    return missing;
  }

  /** The digest of {@code file} by {@code algorithm}, one of sha256, sha1 and md5. */
  static Checksum checksum(Path file, String algorithm) {
    try (InputStream in = Files.newInputStream(file)) {
      // Literal names, so native-image keeps each algorithm.
      var digest =
          switch (algorithm) {
            case "sha256" -> MessageDigest.getInstance("SHA-256");
            case "sha1" -> MessageDigest.getInstance("SHA-1");
            case "md5" -> MessageDigest.getInstance("MD5");
            default ->
                throw new InstallException(
                    "cannot verify "
                        + file.getFileName()
                        + ": unknown checksum algorithm '"
                        + algorithm
                        + "'");
          };
      var buffer = new byte[64 * 1024];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
      return new Checksum(algorithm, HexFormat.of().formatHex(digest.digest()));
    } catch (IOException e) {
      throw new InstallException("cannot read " + file + ": " + e.getMessage(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code 7.8 MB}, in powers of 1024. */
  public static String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    int unit = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
    return "%.1f %sB".formatted(bytes / (double) (1L << (unit * 10)), " KMGTPE".charAt(unit));
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
