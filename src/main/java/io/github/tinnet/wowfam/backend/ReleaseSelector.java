package io.github.tinnet.wowfam.backend;

import static java.util.Comparator.comparing;

import java.util.List;
import java.util.Optional;

/** Picks the release to install from what a backend offers. */
public final class ReleaseSelector {

  private ReleaseSelector() {}

  /** The newest release satisfying {@code requested} ({@code latest}, exact or prefix). */
  public static Optional<Release> newest(List<Release> releases, String requested) {
    return releases.stream()
        .filter(r -> r.version().satisfies(requested))
        .max(comparing(Release::version));
  }

  /**
   * The locked release if it is still offered and still satisfies {@code requested}; otherwise the
   * newest that does.
   */
  public static Optional<Release> preferLocked(
      List<Release> releases, String requested, Optional<String> lockedTag) {
    if (lockedTag.isPresent()) {
      var locked =
          releases.stream()
              .filter(
                  r -> r.tag().equals(lockedTag.orElseThrow()) && r.version().satisfies(requested))
              .findFirst();
      if (locked.isPresent()) {
        return locked;
      }
    }
    return newest(releases, requested);
  }
}
