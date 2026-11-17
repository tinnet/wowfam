package io.github.tinnet.wowfam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Registry;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every well-known addon still resolves to one zip on the real GitHub. Run with {@code gradle
 * networkTest}.
 */
@Tag("network")
final class RegistryNetworkTest {

  private static final GitHubBackend BACKEND =
      new GitHubBackend(
          new GitHubApi(
              Http.client(System.getenv()),
              GitHubApi.DEFAULT_BASE,
              Optional.ofNullable(System.getenv("GITHUB_TOKEN"))));

  static Stream<Registry.Entry> entries() {
    return Registry.all().stream();
  }

  @MethodSource("entries")
  @ParameterizedTest
  void resolvesToARelease(Registry.Entry entry) {
    var spec =
        BACKEND.discover(
            new AddonSpec(
                entry.name(), entry.source(), AddonSpec.LATEST, List.of(), entry.options()));
    var release = ReleaseSelector.newest(BACKEND, spec, BACKEND.releases(spec));
    assertThat(release).as(entry.name()).isPresent();
    assertThat(release.orElseThrow().asset().name()).endsWith(".zip");
  }
}
