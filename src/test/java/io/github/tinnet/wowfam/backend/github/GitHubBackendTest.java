package io.github.tinnet.wowfam.backend.github;

import static io.github.tinnet.wowfam.FakeGitHub.addonZip;
import static io.github.tinnet.wowfam.FakeGitHub.releaseJson;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import io.github.tinnet.wowfam.FakeGitHub;
import io.github.tinnet.wowfam.FakeGitHub.FakeRelease;
import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Source;
import io.github.tinnet.wowfam.util.Version;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class GitHubBackendTest {

  private static final String REPO = "rusty-key/spoken-wow";
  private final FakeGitHub github = new FakeGitHub();

  @AfterEach
  void close() {
    github.close();
  }

  private static AddonSpec spec(Map<String, String> options) {
    return new AddonSpec("spoken", Source.parse("github:" + REPO), "latest", List.of(), options);
  }

  private List<Release> releases(Map<String, String> options) {
    return github.backends().get("github").releases(spec(options));
  }

  /** What would be installed: the newest release the backend can resolve. */
  private Optional<Release> newest(Map<String, String> options) {
    var backend = github.backends().get("github");
    var spec = spec(options);
    return ReleaseSelector.newest(backend, spec, backend.releases(spec));
  }

  private static FakeGitHub.FakeAsset zip(String name) {
    return addonZip(name, "1", Map.of("Addon", ""));
  }

  private long releaseJsonDownloads() {
    return github.requests().stream().filter(r -> r.endsWith("/release.json")).count();
  }

  @Test
  void picksTheZipReleaseJsonListsForTheFlavor() {
    github.release(
        "o/addon",
        "v2.0.0",
        zip("Addon-v2.0.0.zip"),
        zip("Addon-v2.0.0-classic.zip"),
        zip("Addon-v2.0.0-nolib.zip"),
        releaseJson(
            Map.of(
                "Addon-v2.0.0.zip", List.of("mainline"),
                "Addon-v2.0.0-nolib.zip", List.of("mainline"),
                "Addon-v2.0.0-classic.zip", List.of("classic", "bcc"))));
    var backend = github.backends().get("github");
    Function<Map<String, String>, String> picked =
        options -> {
          var spec =
              new AddonSpec("a", Source.parse("github:o/addon"), "latest", List.of(), options);
          return ReleaseSelector.newest(backend, spec, backend.releases(spec))
              .map(r -> r.asset().name())
              .orElse("(none)");
        };

    assertThat(picked.apply(Map.of("flavor", "classic"))).isEqualTo("Addon-v2.0.0-classic.zip");
    assertThat(picked.apply(Map.of("flavor", "Classic_Era"))).isEqualTo("Addon-v2.0.0-classic.zip");
    assertThat(picked.apply(Map.of("flavor", "tbc"))).isEqualTo("Addon-v2.0.0-classic.zip");
    assertThat(picked.apply(Map.of("flavor", "retail"))).isEqualTo("Addon-v2.0.0.zip");
    assertThat(picked.apply(Map.of("flavor", "mainline", "nolib", "true")))
        .isEqualTo("Addon-v2.0.0-nolib.zip");
    assertThat(picked.apply(Map.of("flavor", "cata"))).isEqualTo("(none)");
    // By the game's interface: the same major and minor version is enough.
    assertThat(picked.apply(Map.of("interface", "11509"))).isEqualTo("Addon-v2.0.0-classic.zip");
    assertThat(picked.apply(Map.of("interface", "20506"))).isEqualTo("Addon-v2.0.0-classic.zip");
    assertThat(picked.apply(Map.of("interface", "120107"))).isEqualTo("Addon-v2.0.0.zip");
    // After a patch the addon hasn't caught up with: the closest earlier version of that major.
    assertThat(picked.apply(Map.of("interface", "120200"))).isEqualTo("Addon-v2.0.0.zip");
    assertThat(picked.apply(Map.of("interface", "20600"))).isEqualTo("Addon-v2.0.0-classic.zip");
    // Never a zip for a later version, or for another major.
    assertThat(picked.apply(Map.of("interface", "120004"))).isEqualTo("(none)");
    assertThat(picked.apply(Map.of("interface", "30405"))).isEqualTo("(none)");
    // A flavor named in wowfam.toml wins over the game's interface.
    assertThat(picked.apply(Map.of("interface", "120105", "flavor", "classic")))
        .isEqualTo("Addon-v2.0.0-classic.zip");
    assertThatThrownBy(() -> picked.apply(Map.of("interface", "classic")))
        .hasMessageContaining("interface must be a number");
    // One listing serves them all.
    assertThat(github.requests()).filteredOn(r -> r.contains("/repos/")).hasSize(1);
  }

  @HegelTest
  void anInterfaceNeverTakesAZipForALaterVersionOrAnotherMajor(TestCase tc) {
    int wanted = tc.draw(Generators.integers().min(10_000).max(200_000));
    int built = tc.draw(Generators.integers().min(10_000).max(200_000));
    var metadata = JsonMapper.builder().build().readTree("{\"interface\": " + built + "}");

    int rank = GitHubBackend.interfaceRank(wanted, metadata);

    if (built / 10_000 != wanted / 10_000 || built / 100 > wanted / 100) {
      assertThat(rank).isEqualTo(GitHubBackend.Target.NO_MATCH);
    } else {
      // Closer is better: the rank is the zip's major and minor version.
      assertThat(rank).isEqualTo(built / 100);
    }
  }

  @Test
  void theZipForAFlavorCarriesItsOwnChecksum() {
    github.release(
        "o/addon",
        "v2.0.0",
        zip("Addon-v2.0.0.zip"),
        releaseJson(Map.of("Addon-v2.0.0.zip", List.of("mainline", "classic"))));
    var backend = github.backends().get("github");
    var spec =
        new AddonSpec(
            "a", Source.parse("github:o/addon"), "latest", List.of(), Map.of("flavor", "classic"));

    var listed = backend.releases(spec);
    assertThat(listed).extracting(r -> r.asset().name()).containsExactly("release.json");
    assertThat(releaseJsonDownloads()).isEqualTo(0);

    var release = ReleaseSelector.newest(backend, spec, listed).orElseThrow();
    assertThat(release.asset().name()).isEqualTo("Addon-v2.0.0.zip");
    assertThat(release.asset().url().toString()).endsWith("/download/v2.0.0/Addon-v2.0.0.zip");
    assertThat(release.asset().checksum()).isPresent();
  }

  @Test
  void fallsBackToTheNewestReleaseThatHasTheFlavor() {
    github
        .release(
            REPO,
            "v1.0.0",
            zip("Addon-v1.0.0.zip"),
            releaseJson(Map.of("Addon-v1.0.0.zip", List.of("mainline", "classic"))))
        .release(
            REPO,
            "v1.1.0",
            zip("Addon-v1.1.0.zip"),
            releaseJson(Map.of("Addon-v1.1.0.zip", List.of("mainline", "classic"))))
        .release(
            REPO,
            "v2.0.0",
            zip("Addon-v2.0.0.zip"),
            releaseJson(Map.of("Addon-v2.0.0.zip", List.of("mainline"))));

    assertThat(newest(Map.of("flavor", "classic")))
        .hasValueSatisfying(r -> assertThat(r.tag()).isEqualTo("v1.1.0"));
    // Only the releases tried were looked into: 2.0.0, then 1.1.0, never 1.0.0.
    assertThat(releaseJsonDownloads()).isEqualTo(2);
    assertThat(newest(Map.of("flavor", "mainline")))
        .hasValueSatisfying(r -> assertThat(r.tag()).isEqualTo("v2.0.0"));
  }

  @Test
  void releasesWithoutReleaseJsonAreStillPickedByAsset() {
    github
        .release(REPO, "v1.0.0", zip("Addon-v1.0.0.zip"))
        .release(
            REPO,
            "v2.0.0",
            zip("Addon-v2.0.0.zip"),
            releaseJson(Map.of("Addon-v2.0.0.zip", List.of("mainline"))));

    assertThat(newest(Map.of("flavor", "classic")))
        .hasValueSatisfying(r -> {
          assertThat(r.tag()).isEqualTo("v1.0.0");
          assertThat(r.asset().name()).isEqualTo("Addon-v1.0.0.zip");
        });
  }

  @Test
  void releaseJsonIsIgnoredWithoutAFlavor() {
    github.release(
        REPO,
        "v2.0.0",
        zip("Addon-v2.0.0.zip"),
        releaseJson(Map.of("Addon-v2.0.0.zip", List.of("mainline"))));

    assertThat(newest(Map.of()))
        .hasValueSatisfying(r -> assertThat(r.asset().name()).isEqualTo("Addon-v2.0.0.zip"));
    assertThat(releaseJsonDownloads()).isEqualTo(0);
  }

  @Test
  void assetNarrowsDownSeveralZipsForOneFlavor() {
    github.release(
        REPO,
        "v2.0.0",
        zip("Addon-v2.0.0.zip"),
        zip("Addon_Options-v2.0.0.zip"),
        releaseJson(
            Map.of(
                "Addon-v2.0.0.zip", List.of("classic"),
                "Addon_Options-v2.0.0.zip", List.of("classic"))));

    assertThatThrownBy(() -> newest(Map.of("flavor", "classic")))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("for flavor 'classic'")
        .hasMessageContaining("narrow it down with 'asset'");
    assertThat(newest(Map.of("flavor", "classic", "asset", "Addon_Options-*.zip")))
        .hasValueSatisfying(
            r -> assertThat(r.asset().name()).isEqualTo("Addon_Options-v2.0.0.zip"));
  }

  @Test
  void rejectsAReleaseJsonThatIsNotJson() {
    github.release(
        REPO,
        "v2.0.0",
        zip("Addon-v2.0.0.zip"),
        new FakeGitHub.FakeAsset("release.json", "<html>".getBytes(UTF_8)));

    assertThatThrownBy(() -> newest(Map.of("flavor", "classic")))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("invalid release.json in v2.0.0");
  }

  @Test
  void picksTheAddonOutOfAMonorepoByTagPrefix() {
    github
        .release(REPO, "spoken/v3.8.4", addonZip("Spoken-3.8.4.zip", "3.8.4", Map.of("Spoken", "")))
        .release(
            REPO,
            "readables-audio/v2.3.0",
            addonZip("SpokenReadablesAudio-2.3.0.zip", "2.3.0", Map.of("SpokenReadablesAudio", "")))
        .release(
            REPO, "spoken/v3.8.5", addonZip("Spoken-3.8.5.zip", "3.8.5", Map.of("Spoken", "")));

    var releases = releases(Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip"));

    assertThat(releases).extracting(Release::tag).containsExactly("spoken/v3.8.5", "spoken/v3.8.4");
    assertThat(releases.getFirst().version()).isEqualTo(Version.of("3.8.5"));
    assertThat(releases.getFirst().asset().name()).isEqualTo("Spoken-3.8.5.zip");
    assertThat(releases.getFirst().asset().checksum())
        .hasValueSatisfying(c -> {
          assertThat(c.algorithm()).isEqualTo("sha256");
          assertThat(c.value()).hasSize(64);
        });
  }

  @Test
  void skipsPrereleasesAndDraftsUnlessAsked() {
    github
        .release(REPO, "spoken/v3.8.4", addonZip("Spoken-3.8.4.zip", "3.8.4", Map.of("Spoken", "")))
        .release(
            REPO,
            "spoken/v3.8.5-beta.1",
            addonZip("Spoken-3.8.5-beta.1.zip", "3.8.5-beta.1", Map.of("Spoken", "")))
        .release(
            new FakeRelease(
                REPO,
                "spoken/v3.9.0",
                false,
                true,
                List.of(addonZip("Spoken-3.9.0.zip", "3.9.0", Map.of("Spoken", "")))))
        .release(
            new FakeRelease(
                REPO,
                "spoken/v3.9.1",
                true,
                false,
                List.of(addonZip("Spoken-3.9.1.zip", "3.9.1", Map.of("Spoken", "")))));

    assertThat(releases(Map.of("tag_prefix", "spoken/")))
        .extracting(Release::tag)
        .containsExactly("spoken/v3.8.4");
    assertThat(releases(Map.of("tag_prefix", "spoken/", "prerelease", "true")))
        .extracting(Release::tag)
        .containsExactly("spoken/v3.9.1", "spoken/v3.8.5-beta.1", "spoken/v3.8.4");
  }

  @Test
  void followsPagination() {
    github.pageSize(2);
    for (int i = 0; i < 7; i++) {
      github.release(
          REPO,
          "v1." + i + ".0",
          addonZip("A-1." + i + ".0.zip", "1." + i + ".0", Map.of("A", "")));
    }
    assertThat(releases(Map.of())).hasSize(7).first().extracting(Release::tag).isEqualTo("v1.6.0");
    assertThat(github.requests()).filteredOn(r -> r.contains("/releases")).hasSize(4);
  }

  @Test
  void ambiguousAssetPatternIsAnErrorForThatRelease() {
    github.release(
        REPO,
        "v1.0.0",
        addonZip("A-1.0.0.zip", "1.0.0", Map.of("A", "")),
        addonZip("A-nolib-1.0.0.zip", "1.0.0", Map.of("A", "")));
    var release = releases(Map.of()).getFirst();
    assertThatThrownBy(release::asset)
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("make 'asset' more specific");
  }

  @Test
  void ambiguousOldReleaseDoesNotBlockNewerOnes() {
    github
        .release(
            REPO,
            "v1.0.0",
            addonZip("A-1.0.0.zip", "1.0.0", Map.of("A", "")),
            addonZip("A-1.0.0-classic.zip", "1.0.0", Map.of("A", "")))
        .release(REPO, "v2.0.0", addonZip("A-2.0.0.zip", "2.0.0", Map.of("A", "")));
    var newest = releases(Map.of("asset", "A-*.zip")).getFirst();
    assertThat(newest.tag()).isEqualTo("v2.0.0");
    assertThat(newest.asset().name()).isEqualTo("A-2.0.0.zip");
  }

  @Test
  void unknownRepositoryExplainsItself() {
    assertThatThrownBy(() -> releases(Map.of()))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("HTTP 404")
        .hasMessageContaining("GITHUB_TOKEN");
  }

  @Test
  void parsesNextLink() {
    var header =
        "<https://api.github.com/repositories/1/releases?per_page=100&page=2>; rel=\"next\", "
            + "<https://api.github.com/repositories/1/releases?per_page=100&page=5>; rel=\"last\"";
    assertThat(GitHubApi.nextLink(header))
        .hasToString("https://api.github.com/repositories/1/releases?per_page=100&page=2");
    assertThat(GitHubApi.nextLink("<https://x/y?page=1>; rel=\"prev\"")).isNull();
  }

  @HegelTest
  void versionOfStripsExactlyThePrefix(TestCase tc) {
    var prefix =
        tc.draw(Generators.sampledFrom("", "spoken/", "readables-audio/", "release-"), "prefix");
    var version = tc.draw(Generators.fromRegex("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}"), "version");
    var v = tc.draw(Generators.booleans(), "with v") ? "v" : "";

    assertThat(GitHubBackend.versionOf(prefix + v + version, prefix)).hasValue(Version.of(version));
    assertThat(GitHubBackend.versionOf("other/" + v + version, prefix)).isEmpty();
  }

  private Map<String, String> discover(String repo, Map<String, String> given) {
    var spec = new AddonSpec("x", Source.parse("github:" + repo), "latest", List.of(), given);
    return github.backends().get("github").discover(spec).options();
  }

  @Test
  void discoversTheAddonsTagPrefixInAMonorepoWithACompanionApp() {
    github
        .release(REPO, "manager/v0.1.13", zip("Spoken-Manager-0.1.13-mac-arm64.zip"))
        .release(REPO, "spoken/v3.8.7", zip("Spoken-3.8.7.zip"))
        .release(REPO, "manager/v0.1.14", zip("Spoken-Manager-0.1.14-mac-arm64.zip"));

    assertThat(discover(REPO, Map.of()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip"));
  }

  @Test
  void discoversTheZipNamedLikeTheRepositoryAndSkipsTagsWithoutZips() {
    var repo = "fitzforge/polestar";
    github
        .release(repo, "forge-v0.2.0", zip("polestar-forge-windows-x86_64.zip"))
        .release(
            repo, "client-v0.1.7", new FakeGitHub.FakeAsset("Polestar.Client.dmg", new byte[1]))
        .release(
            repo, "v0.3.7.2", zip("Polestar-0.3.7.2.zip"), zip("Polestar_Recorder-0.3.7.2.zip"));

    assertThat(discover(repo, Map.of())).containsExactly(Map.entry("asset", "Polestar-*.zip"));
  }

  @Test
  void discoversTheNewerTagSchemeWhenBothAreNamedLikeTheRepository() {
    var repo = "Questie/Questie";
    github
        .release(repo, "v11.38.0", zip("Questie-v11.38.0.zip"))
        .release(repo, "bundle/v12.8.0+v1.0.16", zip("Questie-v12.8.0+v1.0.16.zip"));

    assertThat(discover(repo, Map.of()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("tag_prefix", "bundle/", "asset", "Questie-*.zip"));
  }

  @Test
  void keepsWhatTheUserGaveAndNeedsNoAssetForAFlavor() {
    github
        .release(REPO, "other/v1.0.0", zip("Other-1.0.0.zip"))
        .release(
            REPO,
            "spoken/v3.8.7",
            zip("Spoken-3.8.7.zip"),
            zip("Spoken-3.8.7-classic.zip"),
            releaseJson(Map.of("Spoken-3.8.7-classic.zip", List.of("classic"))));

    assertThat(discover(REPO, Map.of("tag_prefix", "other/", "asset", "O*.zip")))
        .containsExactlyInAnyOrderEntriesOf(Map.of("tag_prefix", "other/", "asset", "O*.zip"));
    assertThat(discover(REPO, Map.of("tag_prefix", "spoken/", "flavor", "classic_era")))
        .doesNotContainKey("asset");
  }

  @Test
  void asksWhenItCannotTellWhichZipOrPrefixIsTheAddon() {
    github.release(
        REPO,
        "spoken/v3.8.7",
        zip("Voices-3.8.7.zip"),
        zip("Sounds-3.8.7.zip"),
        releaseJson(Map.of("Voices-3.8.7.zip", List.of("classic"))));
    assertThatThrownBy(() -> discover(REPO, Map.of()))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("Voices-3.8.7.zip, Sounds-3.8.7.zip")
        .hasMessageContaining("set asset")
        .hasMessageContaining("or flavor");

    var repo = "someone/addons";
    github
        .release(repo, "alpha/v1.0.0", zip("Alpha-1.0.0.zip"))
        .release(repo, "beta/v2.0.0", zip("Beta-2.0.0.zip"));
    assertThatThrownBy(() -> discover(repo, Map.of()))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("'beta/', e.g. beta/v2.0.0; 'alpha/', e.g. alpha/v1.0.0")
        .hasMessageContaining("set tag_prefix");
  }

  @HegelTest
  void anAssetPatternIsTheZipNameWithItsVersionStarred(TestCase tc) {
    var prefix = tc.draw(Generators.sampledFrom("", "spoken/", "bundle/", "release-"), "prefix");
    var version = tc.draw(Generators.fromRegex("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}"), "version");
    var v = tc.draw(Generators.booleans(), "v in tag") ? "v" : "";
    var vInName = tc.draw(Generators.booleans(), "v in name") ? v : "";
    var name = tc.draw(Generators.fromRegex("[A-Z][A-Za-z_]{0,12}"), "name");
    var tag = prefix + v + version;

    assertThat(GitHubBackend.prefixOf(tag)).isEqualTo(prefix);
    assertThat(GitHubBackend.withoutVersion(name + "-" + vInName + version + ".zip", v + version))
        .isEqualTo("%s-*.zip", name);
  }
}
