package io.github.tinnet.wowfam.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.tinnet.wowfam.util.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The game an AddOns folder belongs to, read from the files the Battle.net launcher keeps in a WoW
 * install:
 *
 * <pre>
 * World of Warcraft/.build.info          one row per installed product, with its version
 * World of Warcraft/_forever_/.flavor.info  the product of this folder, e.g. wowf
 * World of Warcraft/_forever_/Interface/AddOns
 * </pre>
 *
 * @param product the launcher's product code, e.g. {@code wow}, {@code wow_classic_era}, {@code
 *     wowf}
 * @param version the client version, e.g. {@code 1.60.1.70938}
 * @param interfaceNumber the {@code ## Interface} addons declare for this client, e.g. {@code
 *     16001}
 */
public record Game(String product, String version, int interfaceNumber) {

  static final String FLAVOR_INFO = ".flavor.info";
  static final String BUILD_INFO = ".build.info";

  /**
   * The game whose folder holds {@code addonsDir}, if it is inside a WoW install; empty for any
   * other folder.
   */
  public static Optional<Game> detect(Path addonsDir) {
    for (var dir = addonsDir.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
      var flavorInfo = dir.resolve(FLAVOR_INFO);
      if (Files.isRegularFile(flavorInfo)) {
        return detect(flavorInfo, dir.getParent());
      }
    }
    return Optional.empty();
  }

  private static Optional<Game> detect(Path flavorInfo, @Nullable Path installDir) {
    if (installDir == null || !Files.isRegularFile(installDir.resolve(BUILD_INFO))) {
      return Optional.empty();
    }
    var product =
        readTable(flavorInfo).stream()
            .map(row -> row.getOrDefault("Product Flavor", ""))
            .filter(p -> !p.isBlank())
            .findFirst();
    if (product.isEmpty()) {
      return Optional.empty();
    }
    var rows =
        readTable(installDir.resolve(BUILD_INFO)).stream()
            .filter(row -> product.orElseThrow().equals(row.get("Product")))
            .toList();
    // A product can have several rows (regions); the active one is what the launcher runs.
    return rows.stream()
        .filter(row -> "1".equals(row.get("Active")))
        .findFirst()
        .or(() -> rows.stream().findFirst())
        .map(row -> row.get("Version"))
        .flatMap(v -> interfaceOf(v).map(i -> new Game(product.orElseThrow(), v, i)));
  }

  /** {@code 1.60.1.70938} -> {@code 16001}, {@code 12.1.5.x} -> {@code 120105}. */
  static Optional<Integer> interfaceOf(String version) {
    return Version.parse(version)
        .filter(v -> v.numbers().size() >= 3)
        .map(v ->
            v.numbers().getFirst().intValue() * 10_000
                + v.numbers().get(1).intValue() * 100
                + v.numbers().get(2).intValue());
  }

  /**
   * The launcher's "BPSV" files: a header of {@code Name!TYPE:size} columns, then rows, all
   * separated by {@code |}.
   */
  static List<Map<String, String>> readTable(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, UTF_8);
    } catch (IOException e) {
      return List.of();
    }
    var rows = new ArrayList<Map<String, String>>();
    List<String> header = null;
    for (var raw : lines) {
      var line = raw.replace("\uFEFF", "").strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      var cells = line.split("\\|", -1);
      if (header == null) {
        header = new ArrayList<>();
        for (var cell : cells) {
          int bang = cell.indexOf('!');
          header.add(bang < 0 ? cell : cell.substring(0, bang));
        }
        continue;
      }
      var row = new LinkedHashMap<String, String>();
      for (int i = 0; i < Math.min(cells.length, header.size()); i++) {
        row.put(header.get(i), cells[i]);
      }
      rows.add(row);
    }
    return rows;
  }

  @Override
  public String toString() {
    return product + " " + version + " (interface " + interfaceNumber + ")";
  }
}
