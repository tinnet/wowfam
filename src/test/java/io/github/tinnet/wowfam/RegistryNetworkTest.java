package io.github.tinnet.wowfam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.backend.Http;
import io.github.tinnet.wowfam.backend.ReleaseSelector;
import io.github.tinnet.wowfam.backend.github.GitHubApi;
import io.github.tinnet.wowfam.backend.github.GitHubBackend;
import io.github.tinnet.wowfam.config.AddonSpec;
import io.github.tinnet.wowfam.config.Registry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every well-known addon still resolves to one zip on the real GitHub, the way {@code add} would
 * pick it: for Forever, which the list is for (taking the zip from a release.json, where there is
 * one), and for an AddOns folder whose game is unknown. Run with {@code gradle networkTest}.
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
  void resolvesForForever(Registry.Entry entry) {
    var options = new HashMap<>(entry.options());
    options.put("flavor", "forever");
    assertThat(resolve(entry, options))
        .as(entry.name())
        .hasValueSatisfying(zip -> assertThat(zip).endsWith(".zip"));
  }

  @MethodSource("entries")
  @ParameterizedTest
  void resolvesForAnUnknownGame(Registry.Entry entry) {
    assertThat(resolve(entry, entry.options()))
        .as(entry.name())
        .hasValueSatisfying(zip -> assertThat(zip).endsWith(".zip"));
  }

  /**
   * What add does: discover the rest of the entry, then pick the newest release it resolves. The
   * name of that release's zip.
   */
  private static Optional<String> resolve(Registry.Entry entry, Map<String, String> options) {
    var spec =
        BACKEND.discover(
            new AddonSpec(entry.name(), entry.source(), AddonSpec.LATEST, List.of(), options));
    var release = ReleaseSelector.newest(BACKEND, spec, BACKEND.releases(spec));
    return release.map(r -> r.asset().name());
  }
}
