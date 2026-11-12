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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 *   <li>{@code flavor} — the game flavor to install for, as the BigWigs packager names them: {@code
 *       mainline}, {@code classic}, {@code bcc}, {@code wrath}, {@code cata}, {@code mists}, …
 *       ({@code retail}, {@code classic_era}, {@code tbc} and the like are understood too). A
 *       release that carries the packager's {@code release.json} then installs the zip that file
 *       lists for the flavor, and is passed over if it lists none; {@code asset}, if given, still
 *       has to match. Releases without a {@code release.json} are picked by {@code asset} alone.
 *   <li>{@code nolib} — with {@code flavor}: {@code true} to take the packager's {@code -nolib} zip
 *       rather than the one with libraries embedded, default {@code false}.
 * </ul>
 */
public final class GitHubBackend implements Backend {

  public static final String ID = "github";
  private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

  /** The file the BigWigs packager attaches to a release, describing its zips. */
  static final String RELEASE_JSON = "release.json";

  private static final Map<String, String> FLAVOR_ALIASES =
      Map.ofEntries(
          Map.entry("retail", "mainline"),
          Map.entry("vanilla", "classic"),
          Map.entry("era", "classic"),
          Map.entry("classic_era", "classic"),
          Map.entry("tbc", "bcc"),
          Map.entry("wotlk", "wrath"),
          Map.entry("cataclysm", "cata"),
          Map.entry("mop", "mists"));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final GitHubApi api;

  /**
   * Release lists already fetched, by repository: one list serves every addon of a monorepo, and
   * {@link #resolve} needs the assets of the release it is given.
   */
  private final Map<String, List<GitHubApi.GitHubRelease>> listed = new HashMap<>();

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
    boolean byFlavor = spec.option("flavor").isPresent();

    var result = new ArrayList<Release>();
    for (var release : listed(repo)) {
      if (release.draft() || (release.prerelease() && !prereleases)) {
        continue;
      }
      var version = versionOf(release.tag(), prefix);
      if (version.isEmpty() || (version.orElseThrow().isPrerelease() && !prereleases)) {
        continue;
      }
      var manifest =
          release.assets().stream().filter(a -> a.name().equals(RELEASE_JSON)).findFirst();
      if (byFlavor && manifest.isPresent()) {
        // Which zip is for the flavor is in release.json; resolve() reads it if this release is
        // the one chosen. Until then the release stands in with release.json as its asset.
        result.add(
            new Release(version.orElseThrow(), release.tag(), asset(manifest.orElseThrow())));
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
      result.add(new Release(version.orElseThrow(), release.tag(), asset(matching.getFirst())));
    }
    result.sort(comparing(Release::version).reversed());
    return result;
  }

  /**
   * For an addon with a {@code flavor}, swaps the {@code release.json} standing in as the release's
   * asset for the zip that file lists for the flavor.
   */
  @Override
  public Optional<Release> resolve(AddonSpec spec, Release release) {
    var flavor = spec.option("flavor").map(GitHubBackend::canonicalFlavor);
    if (flavor.isEmpty() || !release.asset().name().equals(RELEASE_JSON)) {
      return Optional.of(release);
    }
    var attached =
        listed(spec.source().reference()).stream()
            .filter(r -> r.tag().equals(release.tag()))
            .findFirst()
            .map(GitHubApi.GitHubRelease::assets)
            .orElseGet(List::of);
    var assetGlob = spec.option("asset").map(Glob::of);
    boolean nolib = spec.option("nolib").filter(Boolean::parseBoolean).isPresent();

    JsonNode manifest;
    try {
      manifest = JSON.readTree(api.text(release.asset().url()));
    } catch (JacksonException e) {
      throw new BackendException(
          "addons."
              + spec.name()
              + ": invalid "
              + RELEASE_JSON
              + " in "
              + release.tag()
              + ": "
              + e.getOriginalMessage(),
          e);
    }
    var names = new LinkedHashSet<String>();
    for (var entry : manifest.path("releases")) {
      var filename = entry.path("filename").asString("");
      boolean forFlavor = false;
      for (var metadata : entry.path("metadata")) {
        forFlavor |= metadata.path("flavor").asString("").equalsIgnoreCase(flavor.orElseThrow());
      }
      if (forFlavor
          && entry.path("nolib").asBoolean(false) == nolib
          && assetGlob.filter(g -> !g.matches(filename)).isEmpty()) {
        names.add(filename);
      }
    }
    var matching = attached.stream().filter(a -> names.contains(a.name())).toList();
    if (matching.size() > 1) {
      throw new BackendException(
          "addons."
              + spec.name()
              + ": "
              + RELEASE_JSON
              + " of "
              + release.tag()
              + " lists "
              + matching.stream().map(GitHubApi.GitHubAsset::name).toList()
              + " for flavor '"
              + flavor.orElseThrow()
              + "'; narrow it down with 'asset'");
    }
    return matching.stream()
        .findFirst()
        .map(a -> new Release(release.version(), release.tag(), asset(a)));
  }

  /** The packager's name for a flavor: {@code retail} is {@code mainline}, and so on. */
  static String canonicalFlavor(String flavor) {
    var lower = flavor.strip().toLowerCase(Locale.ROOT);
    return FLAVOR_ALIASES.getOrDefault(lower, lower);
  }

  private List<GitHubApi.GitHubRelease> listed(String repo) {
    var releases = listed.get(repo);
    if (releases == null) {
      releases = api.releases(repo);
      listed.put(repo, releases);
    }
    return releases;
  }

  private static Asset asset(GitHubApi.GitHubAsset a) {
    return new Asset(a.name(), a.downloadUrl(), a.size(), sha256(a.digest()));
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
