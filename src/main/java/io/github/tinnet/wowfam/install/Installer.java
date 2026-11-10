package io.github.tinnet.wowfam.install;

import io.github.tinnet.wowfam.backend.Backends;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.lock.LockEntry;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
    UP_TO_DATE
  }

  public record Outcome(
      String addon,
      Status status,
      Optional<String> previousVersion,
      String version,
      List<String> folders) {}

  /**
   * @param missingDependencies addon folder -> required folders (from its .toc) not present in the
   *     AddOns directory
   */
  public record Report(List<Outcome> outcomes, Map<String, List<String>> missingDependencies) {}

  private final Backends backends;
  private final Consumer<String> log;

  public Installer(Backends backends, Consumer<String> log) {
    this.backends = backends;
    this.log = log;
  }

  public Report run(Config config, List<String> names, Mode mode, boolean force) {
    var settings = config.settings();
    var lock = LockFile.read(config.lockFile());
    var outcomes = new ArrayList<Outcome>();
    try {
      Files.createDirectories(settings.addonsDir());
    } catch (IOException e) {
      throw new InstallException(
          "cannot create " + settings.addonsDir() + ": " + e.getMessage(), e);
    }
    for (var spec : config.installOrder(names)) {
      outcomes.add(installOne(config, spec, lock, mode, force));
      lock.write();
    }
    return new Report(outcomes, missingDependencies(settings.addonsDir(), lock));
  }

  private Outcome installOne(
      Config config, AddonSpec spec, LockFile lock, Mode mode, boolean force) {
    var addonsDir = config.settings().addonsDir();
    var locked = lock.get(spec.name()).filter(e -> e.source().equals(spec.source().toString()));
    var backend = backends.get(spec.source().backend());
    var releases = backend.releases(spec);
    var lockedTag = mode == Mode.INSTALL ? locked.map(LockEntry::tag) : Optional.<String>empty();
    var release =
        ReleaseSelector.preferLocked(releases, spec.version(), lockedTag)
            .orElseThrow(() ->
                new InstallException(
                    "no release of "
                        + spec.name()
                        + " ("
                        + spec.source()
                        + ") matches version '"
                        + spec.version()
                        + "'"
                        + (releases.isEmpty()
                            ? "; none found at all, check tag_prefix and asset"
                            : "")));

    boolean present =
        locked.isPresent()
            && locked.get().folders().stream()
                .allMatch(f -> Files.isDirectory(addonsDir.resolve(f)));
    if (!force && present && locked.get().tag().equals(release.tag())) {
      log.accept(spec.name() + " " + release.version() + " is up to date");
      return new Outcome(
          spec.name(),
          Status.UP_TO_DATE,
          Optional.of(locked.get().version()),
          release.version().toString(),
          locked.get().folders());
    }

    var archive = fetch(config, spec, release);
    log.accept(
        "installing " + spec.name() + " " + release.version() + " from " + release.asset().name());
    var folders =
        unpack(
            archive,
            addonsDir,
            spec.name(),
            lock,
            locked.map(LockEntry::folders).orElse(List.of()));
    lock.put(
        spec.name(),
        new LockEntry(
            spec.source().toString(),
            release.version().toString(),
            release.tag(),
            release.asset().name(),
            release.asset().url().toString(),
            release.asset().sha256(),
            folders));
    var previous = locked.map(LockEntry::version);
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

  private Path fetch(Config config, AddonSpec spec, Release release) {
    var asset = release.asset();
    var target = config.settings().cacheDir().resolve(spec.name()).resolve(asset.name());
    if (Files.exists(target) && asset.sha256().map(s -> s.equals(sha256(target))).orElse(false)) {
      log.accept("using cached " + asset.name());
      return target;
    }
    log.accept(
        "downloading "
            + asset.name()
            + (asset.size() > 0 ? " (" + humanSize(asset.size()) + ")" : ""));
    backends.get(spec.source().backend()).download(asset, target);
    if (asset.sha256().isPresent()) {
      var actual = sha256(target);
      if (!asset.sha256().get().equals(actual)) {
        throw new InstallException(
            "checksum mismatch for "
                + asset.name()
                + ": expected "
                + asset.sha256().get()
                + ", got "
                + actual);
      }
    }
    return target;
  }

  /**
   * Extracts into a staging folder inside the AddOns directory (same file system, so moves are
   * cheap), then swaps the folders in and removes folders the previous release had but this one
   * does not.
   */
  private List<String> unpack(
      Path archive, Path addonsDir, String addon, LockFile lock, List<String> previousFolders) {
    var staging = addonsDir.resolve(".wowfam-staging-" + addon);
    try {
      deleteRecursively(staging);
      Files.createDirectories(staging);
      var folders = ZipExtractor.extract(archive, staging);
      for (var folder : folders) {
        var owner = lock.ownerOf(folder);
        if (owner.isPresent() && !owner.get().equals(addon)) {
          throw new InstallException(
              addon + " wants to install folder " + folder + ", which belongs to " + owner.get());
        }
      }
      var stale = new LinkedHashSet<>(previousFolders);
      folders.forEach(stale::remove);
      for (var folder : stale) {
        deleteRecursively(addonsDir.resolve(folder));
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

  static String sha256(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[64 * 1024];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    int unit = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
    return String.format("%.1f %sB", bytes / (double) (1L << (unit * 10)), " KMGTPE".charAt(unit));
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
