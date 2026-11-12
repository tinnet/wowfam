package io.github.tinnet.wowfam.backend.github;

import static java.util.Comparator.comparing;

import io.github.tinnet.wowfam.backend.Asset;
import io.github.tinnet.wowfam.backend.Backend;
import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.util.Checksum;
import io.github.tinnet.wowfam.util.Glob;
import io.github.tinnet.wowfam.util.Version;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code source = "github:owner/repo"}: installs a zip attached to a GitHub release.
 *
 * <p>Options:
 *
 * <ul>
 *   <li>{@code tag_prefix} — only tags starting with this are considered, and it is stripped before
 *       the version is parsed. Lets one repository publish several addons, e.g. {@code spoken/} for
 *       tags like {@code spoken/v3.8.5}. A {@code v} after the prefix is always optional.
 *   <li>{@code asset} — glob picking the asset, default {@code *.zip}. Must match exactly one.
 *   <li>{@code prerelease} — {@code true} to consider prereleases, default {@code false}.
 * </ul>
 */
public final class GitHubBackend implements Backend {

  public static final String ID = "github";
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

  private final GitHubApi api;

  public GitHubBackend(GitHubApi api) {
    this.api = api;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<Release> releases(AddonSpec spec) {
    var repo = spec.source().reference();
    if (!REPO.matcher(repo).matches()) {
      throw new ConfigException("addons." + spec.name() + ": '" + repo + "' is not owner/repo");
    }
    var prefix = spec.option("tag_prefix").orElse("");
    var assetGlob = Glob.of(spec.option("asset").orElse("*.zip"));
    boolean prereleases = spec.option("prerelease").filter(Boolean::parseBoolean).isPresent();

    var result = new ArrayList<Release>();
    for (var release : api.releases(repo)) {
      if (release.draft() || (release.prerelease() && !prereleases)) {
        continue;
      }
      var version = versionOf(release.tag(), prefix);
      if (version.isEmpty() || (version.orElseThrow().isPrerelease() && !prereleases)) {
        continue;
      }
      var matching = release.assets().stream().filter(a -> assetGlob.matches(a.name())).toList();
      if (matching.isEmpty()) {
        continue;
      }
      if (matching.size() > 1) {
        throw new BackendException(
            "addons."
                + spec.name()
                + ": asset pattern '"
                + assetGlob
                + "' matches "
                + matching.stream().map(GitHubApi.GitHubAsset::name).toList()
                + " in "
                + release.tag()
                + "; make 'asset' more specific");
      }
      var a = matching.getFirst();
      result.add(
          new Release(
              version.orElseThrow(),
              release.tag(),
              new Asset(a.name(), a.downloadUrl(), a.size(), sha256(a.digest()))));
    }
    result.sort(comparing(Release::version).reversed());
    return result;
  }

  @Override
  public void download(Asset asset, Path target) {
    api.download(asset.url(), target);
  }

  /** The version in {@code tag} after {@code prefix}, if the tag has that prefix and a version. */
  public static Optional<Version> versionOf(String tag, String prefix) {
    if (!tag.startsWith(prefix)) {
      return Optional.empty();
    }
    return Version.parse(tag.substring(prefix.length()));
  }

  /** GitHub's {@code sha256:<hex>} digest; anything else is not something we can verify. */
  private static Optional<Checksum> sha256(Optional<String> digest) {
    return digest.flatMap(Checksum::parse).filter(c -> c.algorithm().equals(Checksum.SHA256));
  }
}
