package io.github.tinnet.wowfam.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One {@code [addons.<name>]} table of {@code wowfam.toml}.
 *
 * @param name the table name; only used to refer to the addon (it need not match a folder)
 * @param source backend and reference
 * @param version {@code latest}, an exact version or a version prefix such as {@code 3.8}
 * @param depends other entries of the same file to install first
 * @param options remaining keys, interpreted by the backend (e.g. {@code tag_prefix}, {@code
 *     asset})
 * @param hooks commands run around this addon's installation, after the global ones
 */
public record AddonSpec(
    String name,
    Source source,
    String version,
    List<String> depends,
    Map<String, String> options,
    Hooks hooks) {

  public static final String LATEST = "latest";

  public AddonSpec {
    depends = List.copyOf(depends);
    options = Map.copyOf(options);
    if (version == null || version.isBlank()) {
      version = LATEST;
    }
  }

  public AddonSpec(
      String name,
      Source source,
      String version,
      List<String> depends,
      Map<String, String> options) {
    this(name, source, version, depends, options, Hooks.NONE);
  }

  public Optional<String> option(String key) {
    return Optional.ofNullable(options.get(key));
  }

  public boolean wantsLatest() {
    return LATEST.equalsIgnoreCase(version);
  }
}
