package io.github.tinnet.wowfam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.Release;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Source;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Talks to the real GitHub API. Run with {@code gradle networkTest}. */
@Tag("network")
final class SpokenNetworkTest {

  @Test
  void findsSpokenReleasesInTheMonorepo() {
    var env = System.getenv();
    var token = Optional.ofNullable(env.get("GITHUB_TOKEN"));
    var backend = new GitHubBackend(new GitHubApi(Http.client(env), GitHubApi.DEFAULT_BASE, token));
    var spec =
        new AddonSpec(
            "spoken",
            Source.parse("github:rusty-key/spoken-wow"),
            "latest",
            List.of(),
            Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip"));

    var releases = backend.releases(spec);

    assertThat(releases).isNotEmpty().allSatisfy(r -> assertThat(r.tag()).startsWith("spoken/"));
    assertThat(releases)
        .extracting(Release::asset)
        .allSatisfy(a -> assertThat(a.name()).matches("Spoken-.*\\.zip"));
  }
}
