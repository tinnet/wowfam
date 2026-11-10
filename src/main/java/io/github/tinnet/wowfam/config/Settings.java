package io.github.tinnet.wowfam.config;

import java.nio.file.Path;

/**
 * The {@code [settings]} table.
 *
 * @param addonsDir where addon folders are unpacked; normally {@code .../Interface/AddOns}
 * @param cacheDir where downloaded archives are kept
 */
public record Settings(Path addonsDir, Path cacheDir) {}
