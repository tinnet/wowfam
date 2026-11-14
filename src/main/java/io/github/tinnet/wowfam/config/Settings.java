package io.github.tinnet.wowfam.config;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a game's addons go, from its {@code [games.<name>]} table and {@code [settings]}.
 *
 * @param addonsDir where addon folders are unpacked; normally {@code .../Interface/AddOns}
 * @param cacheDir where downloaded archives are kept
 * @param game the game {@code addonsDir} belongs to, when it is inside a WoW install
 * @param gameDir the game folder ({@code dir}, e.g. {@code .../World of Warcraft/_forever_}), when
 *     the table names one rather than an {@code addons_dir}
 */
public record Settings(Path addonsDir, Path cacheDir, Optional<Game> game, Optional<Path> gameDir) {

  public Settings(Path addonsDir, Path cacheDir) {
    this(addonsDir, cacheDir, Optional.empty(), Optional.empty());
  }
}
