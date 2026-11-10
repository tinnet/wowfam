package io.github.tinnet.wowfam.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The {@code ## Key: value} metadata of an addon's {@code .toc} file(s). */
public record TocFile(Map<String, String> metadata) {

  public static TocFile parse(String text) {
    var metadata = new LinkedHashMap<String, String>();
    for (var line : text.split("\\R")) {
      if (!line.startsWith("##")) {
        continue;
      }
      var body = line.substring(2);
      int colon = body.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      var key = body.substring(0, colon).strip();
      if (!key.isEmpty()) {
        metadata.putIfAbsent(key, body.substring(colon + 1).strip());
      }
    }
    return new TocFile(Map.copyOf(metadata));
  }

  /**
   * Addon folders that must be present for this one to load ({@code Dependencies}, {@code
   * RequiredDeps}, {@code Dep*}).
   */
  public Set<String> requiredDependencies() {
    var deps = new LinkedHashSet<String>();
    metadata.forEach((key, value) -> {
      var k = key.toLowerCase(Locale.ROOT);
      if (k.equals("dependencies") || k.equals("requireddeps") || k.startsWith("dep")) {
        for (var dep : value.split(",")) {
          if (!dep.isBlank()) {
            deps.add(dep.strip());
          }
        }
      }
    });
    return deps;
  }

  /**
   * Reads every {@code .toc} for the addon in {@code folder}: {@code <Folder>.toc} and the
   * flavor-suffixed variants ({@code <Folder>_Vanilla.toc}, {@code <Folder>-Classic.toc}, ...).
   */
  public static List<TocFile> readAll(Path folder) {
    var name = folder.getFileName().toString();
    try (var files = Files.list(folder)) {
      return files
          .filter(p -> {
            var f = p.getFileName().toString();
            return f.endsWith(".toc")
                && (f.equals(name + ".toc")
                    || f.startsWith(name + "_")
                    || f.startsWith(name + "-"));
          })
          .sorted()
          .map(p -> {
            try {
              return parse(Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException e) {
              throw new InstallException("cannot read " + p + ": " + e.getMessage(), e);
            }
          })
          .toList();
    } catch (IOException e) {
      throw new InstallException("cannot list " + folder + ": " + e.getMessage(), e);
    }
  }
}
