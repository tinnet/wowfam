package io.github.tinnet.wowfam.backend.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.joining;

import io.github.tinnet.wowfam.backend.Asset;
import io.github.tinnet.wowfam.backend.Backend;
import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.util.Checksum;
import io.github.tinnet.wowfam.util.Glob;
import io.github.tinnet.wowfam.util.Version;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.ToIntFunction;
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
 *       has to match. Releases without a {@code release.json} are picked by {@code asset} alone,
 *       but only if they are newer than every release with one.
 *   <li>{@code interface} — like {@code flavor}, but by the {@code ## Interface} number of the game
 *       ({@code 16001}); the release.json entry for the same major and minor version matches, or
 *       else the one for the closest earlier minor version of the same major. Set from the game
 *       folder when the AddOns folder is inside a WoW install and {@code flavor} is not given.
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

  /** Where {@code release.json} files are kept by digest, if anywhere. */
  private final Optional<Path> manifestCache;

  /** {@code release.json} bodies read this run, by URL. */
  private final Map<URI, String> manifests = new HashMap<>();

  public GitHubBackend(GitHubApi api) {
    this.api = api;
    this.manifestCache = Optional.empty();
  }

  /**
   * @param cacheDir wowfam's cache directory: a release's {@code release.json} is downloaded once
   *     and read from there afterwards
   */
  public GitHubBackend(GitHubApi api, Path cacheDir) {
    this.api = api;
    this.manifestCache = Optional.of(cacheDir.resolve(RELEASE_JSON));
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
    boolean byFlavor = target(spec).isPresent();

    record Offer(
        GitHubApi.GitHubRelease release,
        Version version,
        Optional<GitHubApi.GitHubAsset> manifest) {}
    var offered = new ArrayList<Offer>();
    for (var release : listed(repo)) {
      if (release.draft() || (release.prerelease() && !prereleases)) {
        continue;
      }
      var version = versionOf(release.tag(), prefix);
      if (version.isPresent() && (prereleases || !version.orElseThrow().isPrerelease())) {
        offered.add(new Offer(release, version.orElseThrow(), manifestOf(release)));
      }
    }
    // For a game, a release from before the addon shipped release.json can't say which zip is for
    // which game, and its *.zip may be any of them or one from years ago: WeakAuras 3.3.0 for
    // retail, once its newer releases have no retail zip. Releases without one that came later
    // (an addon that stopped using the packager) are still picked by asset.
    var newestWithManifest =
        offered.stream()
            .filter(o -> o.manifest().isPresent())
            .map(Offer::version)
            .max(naturalOrder());

    var result = new ArrayList<Release>();
    for (var offer : offered) {
      var release = offer.release();
      var version = offer.version();
      var manifest = offer.manifest();
      if (byFlavor
          && manifest.isEmpty()
          && newestWithManifest.filter(newest -> version.compareTo(newest) < 0).isPresent()) {
        continue;
      }
      if (byFlavor && manifest.isPresent()) {
        // Which zip is for the flavor is in release.json; resolve() reads it if this release is
        // the one chosen. Until then the release stands in with release.json as its asset.
        result.add(new Release(version, release.tag(), asset(manifest.orElseThrow())));
        continue;
      }
      var matching = release.assets().stream().filter(a -> assetGlob.matches(a.name())).toList();
      if (matching.isEmpty()) {
        continue;
      }
      // Several matches only fail if this release gets picked (Release.asset()): an old release
      // that also shipped a classic zip must not block installing a newer one.
      result.add(
          new Release(
              version, release.tag(), matching.stream().map(GitHubBackend::asset).toList()));
    }
    result.sort(comparing(Release::version).reversed());
    return result;
  }

  /**
   * Works out {@code tag_prefix} and {@code asset} from the repository's releases, as one would by
   * looking at its release page:
   *
   * <ul>
   *   <li>Tags are grouped by what comes before their version ({@code spoken/v3.8.7} is in {@code
   *       spoken/}). Groups whose releases carry no zip are dropped, which leaves out desktop apps
   *       published from the same repository. If several remain, the one named in the repository
   *       name wins; otherwise the user has to say.
   *   <li>The asset is the newest release's zip with its version replaced by {@code *}. If it has
   *       several, the one named like the repository wins ({@code Polestar-*.zip} over {@code
   *       Polestar_Recorder-*.zip} in {@code polestar}).
   *   <li>With {@code flavor}, a release that has a {@code release.json} needs no asset.
   * </ul>
   */
  @Override
  public AddonSpec discover(AddonSpec spec) {
    var repo = spec.source().reference();
    if (!REPO.matcher(repo).matches()) {
      throw new ConfigException("'" + repo + "' is not owner/repo");
    }
    boolean prereleases = spec.option("prerelease").filter(Boolean::parseBoolean).isPresent();
    var published =
        listed(repo).stream().filter(r -> !r.draft() && (prereleases || !r.prerelease())).toList();
    if (published.isEmpty()) {
      throw new BackendException(repo + " has no published releases on GitHub");
    }
    var options = new LinkedHashMap<>(spec.options());
    var prefix = spec.option("tag_prefix").orElseGet(() -> tagPrefix(spec, published));
    if (!prefix.isEmpty()) {
      options.put("tag_prefix", prefix);
    }
    var newest =
        published.stream()
            .filter(r ->
                versionOf(r.tag(), prefix)
                    .filter(v -> prereleases || !v.isPrerelease())
                    .isPresent())
            .max(comparing(r -> versionOf(r.tag(), prefix).orElseThrow()))
            .orElseThrow(() ->
                new BackendException(
                    "no release of " + repo + " has a tag like " + prefix + "v1.2.3"));
    boolean byFlavor = target(spec).isPresent() && manifestOf(newest).isPresent();
    if (spec.option("asset").isEmpty() && !byFlavor) {
      options.put("asset", assetPattern(spec, newest, prefix));
    }
    return new AddonSpec(
        spec.name(), spec.source(), spec.version(), spec.depends(), options, spec.hooks());
  }

  /** What comes before the version in {@code tag}: up to its first digit, less a {@code v}. */
  static String prefixOf(String tag) {
    int digit = 0;
    while (digit < tag.length() && !Character.isDigit(tag.charAt(digit))) {
      digit++;
    }
    var prefix = tag.substring(0, digit);
    return prefix.endsWith("v") || prefix.endsWith("V")
        ? prefix.substring(0, prefix.length() - 1)
        : prefix;
  }

  private static String tagPrefix(AddonSpec spec, List<GitHubApi.GitHubRelease> releases) {
    var repo = spec.source().reference();
    // Newest first, as GitHub lists them, so each group's first release is its latest.
    var groups = new LinkedHashMap<String, List<GitHubApi.GitHubRelease>>();
    for (var release : releases) {
      var prefix = prefixOf(release.tag());
      if (versionOf(release.tag(), prefix).isPresent()) {
        groups.computeIfAbsent(prefix, p -> new ArrayList<>()).add(release);
      }
    }
    groups
        .values()
        .removeIf(group ->
            group.stream().flatMap(r -> r.assets().stream()).noneMatch(a -> isZip(a.name())));
    if (groups.isEmpty()) {
      throw new BackendException(
          "no release of " + repo + " has both a version tag and a zip; is it an addon?");
    }
    if (groups.size() == 1) {
      return groups.firstEntry().getKey();
    }
    // Several things are released from the repository, e.g. an addon and its desktop companion.
    // The addon is the one named like the repository; if that leaves several (a monorepo that
    // moved to a new tag scheme), the one released most recently.
    var repoName = normalized(spec.source().shortName());
    var named =
        groups.entrySet().stream()
            .filter(e -> namedLike(repoName, e.getKey(), e.getValue().getFirst()))
            .map(Map.Entry::getKey)
            .toList();
    if (!named.isEmpty()) {
      return named.getFirst();
    }
    throw new BackendException(
        repo
            + " publishes several things under different tag prefixes ("
            + groups.entrySet().stream()
                .map(e -> "'" + e.getKey() + "', e.g. " + e.getValue().getFirst().tag())
                .collect(joining("; "))
            + "); set tag_prefix to the addon's");
  }

  /**
   * Whether the releases tagged {@code prefix} look like the repository's addon: the prefix is part
   * of the repository name ({@code spoken/} in {@code spoken-wow}), or so is the name of a zip of
   * its latest release ({@code Polestar-*.zip} in {@code polestar}).
   */
  private static boolean namedLike(String repoName, String prefix, GitHubApi.GitHubRelease latest) {
    var p = normalized(prefix);
    if (!p.isEmpty() && repoName.contains(p)) {
      return true;
    }
    var version = latest.tag().substring(prefix.length());
    return latest.assets().stream()
        .map(GitHubApi.GitHubAsset::name)
        .filter(n -> isZip(n))
        .map(n -> normalized(stem(withoutVersion(n, version))))
        .anyMatch(stem -> !stem.isEmpty() && repoName.contains(stem));
  }

  private static String assetPattern(
      AddonSpec spec, GitHubApi.GitHubRelease release, String prefix) {
    var repo = spec.source().reference();
    var zips = release.assets().stream().map(GitHubApi.GitHubAsset::name).filter(n -> isZip(n));
    var version = release.tag().substring(prefix.length());
    var patterns = zips.map(n -> withoutVersion(n, version)).distinct().toList();
    if (patterns.isEmpty()) {
      throw new BackendException(
          release.tag() + " of " + repo + " has no zip; set asset to the archive to install");
    }
    var repoName = normalized(spec.source().shortName());
    var chosen =
        patterns.size() == 1
            ? patterns
            : patterns.stream().filter(p -> normalized(stem(p)).equals(repoName)).toList();
    if (chosen.size() == 1) {
      var glob = Glob.of(chosen.getFirst());
      if (release.assets().stream().filter(a -> glob.matches(a.name())).count() == 1) {
        return chosen.getFirst();
      }
    }
    boolean manifest = manifestOf(release).isPresent();
    throw new BackendException(
        release.tag()
            + " of "
            + repo
            + " has several zips ("
            + release.assets().stream()
                .map(GitHubApi.GitHubAsset::name)
                .filter(n -> isZip(n))
                .collect(joining(", "))
            + "); set asset to a pattern for the one to install, e.g. '"
            + patterns.getFirst()
            + "'"
            + (manifest ? ", or flavor to the game version (it has a " + RELEASE_JSON + ")" : ""));
  }

  /** {@code name} with the version from the tag replaced by {@code *}, with or without its v. */
  static String withoutVersion(String name, String version) {
    var bare = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
    for (var v : List.of(version, bare)) {
      if (!v.isEmpty() && name.contains(v)) {
        return name.replace(v, "*");
      }
    }
    return name;
  }

  /**
   * The part of an asset pattern before its version, e.g. {@code Polestar} of {@code
   * Polestar-*.zip}.
   */
  private static String stem(String pattern) {
    int star = pattern.indexOf('*');
    return star < 0
        ? pattern.substring(0, pattern.length() - ".zip".length())
        : pattern.substring(0, star);
  }

  private static String normalized(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /** The release's {@code release.json}, if the packager attached one. */
  private static Optional<GitHubApi.GitHubAsset> manifestOf(GitHubApi.GitHubRelease release) {
    return release.assets().stream().filter(a -> a.name().equals(RELEASE_JSON)).findFirst();
  }

  private static boolean isZip(String name) {
    return name.toLowerCase(Locale.ROOT).endsWith(".zip");
  }

  /**
   * For an addon with a {@code flavor}, swaps the {@code release.json} standing in as the release's
   * asset for the zip that file lists for the flavor.
   */
  @Override
  public Optional<Release> resolve(AddonSpec spec, Release release) {
    var target = target(spec);
    if (target.isEmpty()
        || release.assets().stream().noneMatch(a -> a.name().equals(RELEASE_JSON))) {
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
      manifest = JSON.readTree(manifestText(release.asset()));
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
    // Each entry is ranked by how well it fits the game; only the best fitting ones are kept.
    var names = new LinkedHashSet<String>();
    int best = Target.NO_MATCH;
    for (var entry : manifest.path("releases")) {
      var filename = entry.path("filename").asString("");
      if (entry.path("nolib").asBoolean(false) != nolib
          || assetGlob.filter(g -> !g.matches(filename)).isPresent()) {
        continue;
      }
      int rank = Target.NO_MATCH;
      for (var metadata : entry.path("metadata")) {
        rank = Math.max(rank, target.orElseThrow().rank().applyAsInt(metadata));
      }
      if (rank > best) {
        best = rank;
        names.clear();
      }
      if (rank == best && rank != Target.NO_MATCH) {
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
              + " for "
              + target.orElseThrow().description()
              + "; narrow it down with 'asset'");
    }
    return matching.stream()
        .findFirst()
        .map(a -> new Release(release.version(), release.tag(), asset(a)));
  }

  /**
   * What an entry of {@code release.json} has to be for: the {@code flavor} the user named, else
   * the {@code interface} of the game the AddOns folder belongs to.
   *
   * @param rank how well an entry's metadata fits, higher is better, {@link #NO_MATCH} for not at
   *     all
   */
  record Target(String description, ToIntFunction<JsonNode> rank) {
    static final int NO_MATCH = -1;
  }

  static Optional<Target> target(AddonSpec spec) {
    var flavor = spec.option("flavor").map(GitHubBackend::canonicalFlavor);
    if (flavor.isPresent()) {
      var f = flavor.orElseThrow();
      return Optional.of(
          new Target(
              "flavor '" + f + "'",
              m -> m.path("flavor").asString("").equalsIgnoreCase(f) ? 0 : Target.NO_MATCH));
    }
    return spec.option("interface")
        .map(i -> {
          int wanted;
          try {
            wanted = Integer.parseInt(i.strip());
          } catch (NumberFormatException e) {
            throw new ConfigException(
                "addons."
                    + spec.name()
                    + ".interface must be a number like 11508, not '"
                    + i
                    + "'");
          }
          return new Target("interface " + wanted, m -> interfaceRank(wanted, m));
        });
  }

  /**
   * How well a zip built for {@code metadata}'s interface fits a game of interface {@code wanted}.
   * The same major and minor version is best (11509 takes 11508). After a game patch an addon that
   * has not caught up is still installed, from the zip for the closest earlier minor version of the
   * same major (11.2 takes 11.1); a zip for a later version or another major never is.
   */
  static int interfaceRank(int wanted, JsonNode metadata) {
    int built = metadata.path("interface").asInt(-1);
    if (built <= 0 || built / 10_000 != wanted / 10_000 || built / 100 > wanted / 100) {
      return Target.NO_MATCH;
    }
    return built / 100;
  }

  /** The packager's name for a flavor: {@code retail} is {@code mainline}, and so on. */
  static String canonicalFlavor(String flavor) {
    var lower = flavor.strip().toLowerCase(Locale.ROOT);
    return FLAVOR_ALIASES.getOrDefault(lower, lower);
  }

  /**
   * The body of a {@code release.json}. One whose digest GitHub publishes never changes under that
   * digest, so it is kept in the cache by digest; every install, update and outdated would
   * otherwise download it again for each addon.
   */
  private String manifestText(Asset asset) {
    var known = manifests.get(asset.url());
    if (known != null) {
      return known;
    }
    var cached =
        asset
            .checksum()
            .filter(c -> c.algorithm().equals(Checksum.SHA256))
            .flatMap(c -> manifestCache.map(dir -> dir.resolve(c.value() + ".json")));
    String text = null;
    if (cached.isPresent() && Files.isRegularFile(cached.orElseThrow())) {
      try {
        text = Files.readString(cached.orElseThrow());
      } catch (IOException e) {
        // Unreadable cache: fall through and download it again.
      }
    }
    if (text == null) {
      text = api.text(asset.url());
      var body = text;
      // Only what matches the digest is kept, so the cache can't hold a corrupt copy.
      cached
          .filter(file -> asset.checksum().orElseThrow().value().equals(sha256Hex(body)))
          .ifPresent(file -> store(file, body));
    }
    manifests.put(asset.url(), text);
    return text;
  }

  private static void store(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      var temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, text);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      // A cache that can't be written only costs a download next time.
    }
  }

  private static String sha256Hex(String text) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
    api.download(asset.url(), target, done -> {});
  }

  @Override
  public void download(Asset asset, Path target, LongConsumer progress) {
    api.download(asset.url(), target, progress);
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
