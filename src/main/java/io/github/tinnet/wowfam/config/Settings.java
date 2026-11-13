package io.github.tinnet.wowfam.config;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code [settings]} table.
 *
 * @param addonsDir where addon folders are unpacked; normally {@code .../Interface/AddOns}
 * @param cacheDir where downloaded archives are kept
 * @param game the game {@code addonsDir} belongs to, when it is inside a WoW install
 */
public record Settings(Path addonsDir, Path cacheDir, Optional<Game> game) {

  public Settings(Path addonsDir, Path cacheDir) {
    this(addonsDir, cacheDir, Optional.empty());
  }
}
