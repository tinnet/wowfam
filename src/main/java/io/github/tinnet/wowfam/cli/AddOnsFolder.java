package io.github.tinnet.wowfam.cli;

import static java.util.Comparator.naturalOrder;

import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** What is in a game's AddOns folder, and who it belongs to. */
final class AddOnsFolder {

  private AddOnsFolder() {}

  /**
   * The addon folders in {@code dir}, by name ignoring case; none if it doesn't exist. Hidden
   * folders (wowfam's staging folders among them) are left out.
   */
  static List<String> folders(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(f -> !f.startsWith("."))
          .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder()))
          .toList();
    } catch (IOException e) {
      throw new ConfigException("cannot list " + dir + ": " + e.getMessage(), e);
    }
  }

  /** Folders in AddOns that no entry of the lock owns. */
  static List<String> unmanaged(Config config, LockFile lock) {
    return folders(config.settings().addonsDir()).stream()
        .filter(f -> lock.owners(f).isEmpty())
        .toList();
  }

  /**
   * Folders in AddOns that neither the lock nor an external entry accounts for. Links are left out:
   * a folder linked into AddOns (an addon being developed) is plainly the user's own.
   */
  static List<String> unclaimed(Config config, LockFile lock) {
    var dir = config.settings().addonsDir();
    return unmanaged(config, lock).stream()
        .filter(f -> config.externalOwner(f).isEmpty())
        .filter(f -> !Files.isSymbolicLink(dir.resolve(f)))
        .toList();
  }

  /** The folders in AddOns that are links, with where each points. */
  static Map<String, Path> links(Path dir) {
    var links = new LinkedHashMap<String, Path>();
    for (var folder : folders(dir)) {
      var path = dir.resolve(folder);
      if (Files.isSymbolicLink(path)) {
        try {
          links.put(folder, Files.readSymbolicLink(path));
        } catch (IOException e) {
          throw new ConfigException("cannot read link " + path + ": " + e.getMessage(), e);
        }
      }
    }
    return links;
  }
}
