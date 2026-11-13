package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class SourceTest {

  @Test
  void readsWhatUsersPaste() {
    var polestar = new Source("github", "fitzforge/polestar");
    assertThat(Source.fromInput("github:fitzforge/polestar")).isEqualTo(polestar);
    assertThat(Source.fromInput("fitzforge/polestar")).isEqualTo(polestar);
    assertThat(Source.fromInput("https://github.com/fitzforge/polestar")).isEqualTo(polestar);
    assertThat(Source.fromInput(" https://www.github.com/fitzforge/polestar/ "))
        .isEqualTo(polestar);
    assertThat(Source.fromInput("github.com/fitzforge/polestar.git")).isEqualTo(polestar);
    assertThat(Source.fromInput("https://github.com/fitzforge/polestar/releases/tag/v0.3.7"))
        .isEqualTo(polestar);
    assertThat(Source.fromInput("https://github.com/rusty-key/spoken-wow#readme"))
        .isEqualTo(new Source("github", "rusty-key/spoken-wow"));
    assertThat(polestar.shortName()).isEqualTo("polestar");
  }

  @Test
  void rejectsWhatIsNeitherASourceNorARepository() {
    assertThatThrownBy(() -> Source.fromInput("polestar"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("expected <backend>:<reference>");
  }
}
