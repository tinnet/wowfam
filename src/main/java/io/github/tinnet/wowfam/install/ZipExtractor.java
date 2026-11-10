package io.github.tinnet.wowfam.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/**
 * Unpacks an addon archive. An addon zip holds one or more top-level folders, each an addon (the
 * Spoken zip, for one, carries Spoken, Spoken_Quests, Spoken_Places and more).
 */
public final class ZipExtractor {

  private ZipExtractor() {}

  /**
   * Extracts {@code zip} into {@code dir} and returns the top-level folder names, sorted.
   *
   * @throws InstallException if an entry would land outside {@code dir}, or the archive has files
   *     at its top level (it is then not laid out as addon folders)
   */
  public static List<String> extract(Path zip, Path dir) {
    var folders = new TreeSet<String>();
    try (var file = new ZipFile(zip.toFile())) {
      var entries = file.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        var relative = safeRelativePath(entry.getName());
        if (relative.isEmpty()) {
          continue;
        }
        var parts = relative.orElseThrow();
        if (parts.size() == 1 && !entry.isDirectory()) {
          throw new InstallException(
              zip.getFileName()
                  + " has a file at its top level ("
                  + parts.getFirst()
                  + "); expected only addon folders");
        }
        folders.add(parts.getFirst());
        var target = dir;
        for (var p : parts) {
          target = target.resolve(p);
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          try (var in = file.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    } catch (IOException e) {
      throw new InstallException("cannot extract " + zip + ": " + e.getMessage(), e);
    }
    if (folders.isEmpty()) {
      throw new InstallException(zip.getFileName() + " contains no addon folders");
    }
    return List.copyOf(folders);
  }

  /**
   * The path segments of a zip entry name, or empty for entries to skip (macOS metadata, empty
   * names).
   *
   * @throws InstallException for names that are absolute or climb out with {@code ..}
   */
  static Optional<List<String>> safeRelativePath(String name) {
    var normalized = name.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
      throw new InstallException("refusing absolute path in archive: " + name);
    }
    var parts = new ArrayList<String>();
    for (var part : normalized.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        throw new InstallException("refusing path that leaves the target folder: " + name);
      }
      if (part.indexOf('\0') >= 0 || part.indexOf(':') >= 0) {
        throw new InstallException("refusing unsafe path in archive: " + name);
      }
      parts.add(part);
    }
    if (parts.isEmpty()
        || parts.getFirst().equals("__MACOSX")
        || parts.getLast().equals(".DS_Store")) {
      return Optional.empty();
    }
    return Optional.of(List.copyOf(parts));
  }
}
