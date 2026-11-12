package io.github.tinnet.wowfam.backend;

import static java.util.Comparator.comparing;

import io.github.tinnet.wowfam.config.AddonSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Picks the release to install from what a backend offers. */
public final class ReleaseSelector {

  private ReleaseSelector() {}

  /**
   * The newest of {@code releases} that satisfies the addon's {@code version} ({@code latest},
   * exact or prefix) and that the backend can {@linkplain Backend#resolve resolve}.
   */
  public static Optional<Release> newest(Backend backend, AddonSpec spec, List<Release> releases) {
    return preferLocked(backend, spec, releases, Optional.empty());
  }

  /**
   * The locked release if it is still offered and still satisfies the addon's {@code version};
   * otherwise the newest that does.
   */
  public static Optional<Release> preferLocked(
      Backend backend, AddonSpec spec, List<Release> releases, Optional<String> lockedTag) {
    var candidates = new ArrayList<Release>();
    releases.stream()
        .filter(r -> r.version().satisfies(spec.version()))
        .sorted(comparing(Release::version).reversed())
        .forEach(candidates::add);
    if (lockedTag.isPresent()) {
      // Stable, so the locked release moves to the front and the rest stay newest first.
      candidates.sort(comparing(r -> !r.tag().equals(lockedTag.orElseThrow())));
    }
    for (var candidate : candidates) {
      var resolved = backend.resolve(spec, candidate);
      if (resolved.isPresent()) {
        return resolved;
      }
    }
    return Optional.empty();
  }
}
