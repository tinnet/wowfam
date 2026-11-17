package io.github.tinnet.wowfam.install;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** The {@code ## Key: value} metadata of an addon's {@code .toc} file(s). */
public record TocFile(Map<String, String> metadata) {

  public static TocFile parse(String text) {
    try {
      return parse(new BufferedReader(new StringReader(text)), key -> true);
    } catch (IOException e) {
      throw new UncheckedIOException(e); // a StringReader doesn't fail
    }
  }

  /** The {@code ##} lines of {@code toc} whose key {@code keep} accepts. */
  private static TocFile parse(BufferedReader toc, Predicate<String> keep) throws IOException {
    var metadata = new LinkedHashMap<String, String>();
    boolean first = true;
    for (String line; (line = toc.readLine()) != null; ) {
      // A byte-order mark would hide the first line's "##".
      if (first && line.startsWith("\uFEFF")) {
        line = line.substring(1);
      }
      first = false;
      if (!line.startsWith("##")) {
        continue;
      }
      int colon = line.indexOf(':', 2);
      if (colon <= 2) {
        continue;
      }
      var key = line.substring(2, colon).strip();
      if (!key.isEmpty() && keep.test(key)) {
        metadata.putIfAbsent(key, line.substring(colon + 1).strip());
      }
    }
    return new TocFile(Map.copyOf(metadata));
  }

  /** Whether {@code key} names required dependencies: {@code Dependencies}, {@code Dep*}... */
  private static boolean isDependencyKey(String key) {
    var k = key.toLowerCase(Locale.ROOT);
    return k.equals("dependencies") || k.equals("requireddeps") || k.startsWith("dep");
  }

  /**
   * Addon folders that must be present for this one to load ({@code Dependencies}, {@code
   * RequiredDeps}, {@code Dep*}).
   */
  public Set<String> requiredDependencies() {
    var deps = new LinkedHashSet<String>();
    metadata.forEach((key, value) -> {
      if (isDependencyKey(key)) {
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
   * replacement characters; the {@code ##} keys are ASCII either way. Only the dependency keys are
   * kept: some addons store data in their {@code .toc} (QuestieDB's run to tens of megabytes and
   * close to 100,000 keys each), and reading them must not cost more than a line scan.
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
            var decoder =
                UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (var toc =
                new BufferedReader(
                    new InputStreamReader(Files.newInputStream(p), decoder), 1 << 16)) {
              return Stream.of(parse(toc, TocFile::isDependencyKey));
            } catch (IOException | UncheckedIOException e) {
              return Stream.empty();
            }
          })
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }
}
