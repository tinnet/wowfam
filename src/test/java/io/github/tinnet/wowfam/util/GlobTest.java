package io.github.tinnet.wowfam.util;

import static dev.hegel.Generators.text;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import org.junit.jupiter.api.Test;

class GlobTest {

  @Test
  void matchesAssetNames() {
    assertThat(Glob.of("Spoken-*.zip").matches("Spoken-3.8.5.zip")).isTrue();
    assertThat(Glob.of("Spoken-*.zip").matches("Spoken_Quests-3.8.5.zip")).isFalse();
    assertThat(Glob.of("*.zip").matches("SpokenReadablesAudio-2.3.0.zip")).isTrue();
    assertThat(Glob.of("*.zip").matches("notes.md")).isFalse();
    assertThat(Glob.of("addon-?.zip").matches("ADDON-1.ZIP")).isTrue();
  }

  @HegelTest
  void aLiteralMatchesOnlyItself(TestCase tc) {
    var literal = tc.draw(text().excludeCharacters("*?").maxSize(20), "literal");
    var other = tc.draw(text().maxSize(20), "other");
    assertThat(Glob.of(literal).matches(literal)).isTrue();
    if (!other.equalsIgnoreCase(literal)) {
      assertThat(Glob.of(literal).matches(other)).isFalse();
    }
  }

  @HegelTest
  void starMatchesAnySuffix(TestCase tc) {
    var prefix = tc.draw(text().excludeCharacters("*?").maxSize(10), "prefix");
    var suffix = tc.draw(text().maxSize(10), "suffix");
    assertThat(Glob.of(prefix + "*").matches(prefix + suffix)).isTrue();
  }
}
