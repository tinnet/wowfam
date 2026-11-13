package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.config.AddonSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A place addons are published, selected by the part of {@code source} before the colon. Mirrors
 * mise's backends: the first is {@code github} (GitHub releases).
 */
public interface Backend {

  /** The prefix used in {@code source = "<id>:..."}. */
  String id();

  /**
   * Every release of the addon this backend can offer, each with the asset to install, newest
   * first. Choosing among them (requested version, lock file) is not the backend's job.
   */
  List<Release> releases(AddonSpec spec);

  /**
   * Fills in the options a new addon from {@code spec.source()} needs, for {@code wowfam add}: what
   * the user gave stays, the rest is worked out from what the source publishes.
   *
   * @throws BackendException when it cannot decide on its own, saying which option to set
   */
  default AddonSpec discover(AddonSpec spec) {
    return spec;
  }

  /**
   * Settles a release picked from {@link #releases} before it is installed or reported. A backend
   * that needs another request to know which asset a release offers this addon (GitHub with {@code
   * flavor}) makes it here, for the one release that matters, not for every release it lists.
   *
   * @return the release with its final asset, or empty if it turns out to have nothing for this
   *     addon, in which case the next best release is tried
   */
  default Optional<Release> resolve(AddonSpec spec, Release release) {
    return Optional.of(release);
  }

  /** Downloads the asset to {@code target}, replacing it if present. */
  void download(Asset asset, Path target);
}
