package io.github.tinnet.wowfam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** A WoW install as the Battle.net launcher leaves it, for tests of game detection. */
public final class WowInstall {

  private WowInstall() {}

  /**
   * Creates {@code root/World of Warcraft} with a {@code .build.info} holding {@code buildInfoRows}
   * ({@code branch|active|build key|version|product}) and a flavor folder with {@code .flavor.info}
   * and {@code Interface/AddOns} per entry of {@code folderToProduct}.
   */
  public static Path create(Path root, Map<String, String> folderToProduct, String buildInfoRows)
      throws IOException {
    var wow = root.resolve("World of Warcraft");
    Files.createDirectories(wow);
    Files.writeString(
        wow.resolve(".build.info"),
        "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\n"
            + buildInfoRows);
    for (var e : folderToProduct.entrySet()) {
      var folder = wow.resolve(e.getKey());
      Files.createDirectories(folder.resolve("Interface/AddOns"));
      Files.writeString(
          folder.resolve(".flavor.info"), "Product Flavor!STRING:0\n" + e.getValue() + "\n");
    }
    return wow;
  }
}
