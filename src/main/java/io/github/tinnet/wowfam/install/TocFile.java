package io.github.tinnet.wowfam.install;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The {@code ## Key: value} metadata of an addon's {@code .toc} file(s). */
public record TocFile(Map<String, String> metadata) {

  public static TocFile parse(String text) {
    var metadata = new LinkedHashMap<String, String>();
    // A byte-order mark would hide the first line's "##".
    for (var line : text.replace("\uFEFF", "").split("\\R")) {
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
   *
   * <p>Only used to warn about missing dependencies, so a file that can't be read is skipped rather
   * than failing the run, and text that isn't UTF-8 (older addons save in Latin-1) is decoded with
   * replacement characters; the {@code ##} keys are ASCII either way.
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
          .flatMap(p -> {
            try {
              return Stream.of(
                  parse(
                      UTF_8
                          .newDecoder()
                          .onMalformedInput(CodingErrorAction.REPLACE)
                          .onUnmappableCharacter(CodingErrorAction.REPLACE)
                          .decode(ByteBuffer.wrap(Files.readAllBytes(p)))
                          .toString()));
            } catch (IOException e) {
              return Stream.empty();
            }
          })
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }
}
