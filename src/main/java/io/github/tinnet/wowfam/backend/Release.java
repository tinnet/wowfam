package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.util.Version;
import java.util.List;

/**
 * One installable release of an addon.
 *
 * @param version parsed from the tag
 * @param tag the backend's identifier for the release (a git tag for GitHub)
 * @param assets the archives that match the addon's asset pattern; installing needs exactly one,
 *     but an ambiguous release only matters once it is the one picked
 */
public record Release(Version version, String tag, List<Asset> assets) {

  public Release {
    assets = List.copyOf(assets);
    if (assets.isEmpty()) {
      throw new IllegalArgumentException("a release needs at least one asset");
    }
  }

  public Release(Version version, String tag, Asset asset) {
    this(version, tag, List.of(asset));
  }

  /** The archive to install. */
  public Asset asset() {
    if (assets.size() > 1) {
      throw new BackendException(
          "the asset pattern matches "
              + assets.stream().map(Asset::name).toList()
              + " in "
              + tag
              + "; make 'asset' more specific");
    }
    return assets.getFirst();
  }
}
