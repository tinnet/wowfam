package io.github.tinnet.wowfam.backend.github;

import static io.github.tinnet.wowfam.FakeGitHub.addonZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import io.github.tinnet.wowfam.FakeGitHub;
import io.github.tinnet.wowfam.FakeGitHub.FakeRelease;
import io.github.tinnet.wowfam.backend.BackendException;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Source;
import io.github.tinnet.wowfam.util.Version;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    assertThat(releases.getFirst().asset().sha256())
        .hasValueSatisfying(s -> assertThat(s).hasSize(64));
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
  void ambiguousAssetPatternIsAnError() {
    github.release(
        REPO,
        "v1.0.0",
        addonZip("A-1.0.0.zip", "1.0.0", Map.of("A", "")),
        addonZip("A-nolib-1.0.0.zip", "1.0.0", Map.of("A", "")));
    assertThatThrownBy(() -> releases(Map.of()))
        .isInstanceOf(BackendException.class)
        .hasMessageContaining("make 'asset' more specific");
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
}
