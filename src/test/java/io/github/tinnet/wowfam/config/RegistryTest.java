package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RegistryTest {

  private static final Set<String> OPTIONS = Set.of("tag_prefix", "asset", "prerelease");

  @Test
  void everyEntryIsSomethingAddCouldWrite() {
    assertThat(Registry.all())
        .isNotEmpty()
        .allSatisfy(e -> {
          assertThat(e.name())
              .matches(ConfigEditor.NAME)
              .isEqualTo(e.name().toLowerCase(Locale.ROOT));
          assertThat(Registry.isName(e.name())).isTrue();
          assertThat(Source.parse(e.source().toString())).isEqualTo(e.source());
          assertThat(e.options().keySet()).isSubsetOf(OPTIONS);
          // The table it becomes must parse like one written by hand.
          ConfigEditor.addonTable(
              "g", new AddonSpec(e.name(), e.source(), AddonSpec.LATEST, List.of(), e.options()));
        });
  }

  @Test
  void namesAreUniqueAndSorted() {
    var names = Registry.all().stream().map(Registry.Entry::name).toList();
    assertThat(names).doesNotHaveDuplicates().isSorted();
  }

  @Test
  void entriesSharingARepositoryAreTellableApart() {
    var all = Registry.all();
    for (var a : all) {
      for (var b : all) {
        if (!Objects.equals(a, b) && a.source().sameAs(b.source())) {
          assertThat(a.options())
              .as("%s and %s need a tag_prefix or asset of their own", a.name(), b.name())
              .isNotEqualTo(b.options());
        }
      }
    }
  }

  @Test
  void looksUpNamesIgnoringCase() {
    assertThat(Registry.lookup("Questie"))
        .get()
        .extracting(Registry.Entry::source)
        .isEqualTo(Source.parse("github:Questie/Questie"));
    assertThat(Registry.lookup("spoken").orElseThrow().options())
        .containsEntry("tag_prefix", "spoken/");
    assertThat(Registry.lookup("nonesuch")).isEmpty();
  }

  @Test
  void onlyBareWordsAreNames() {
    assertThat(Registry.isName("questie")).isTrue();
    assertThat(Registry.isName("spoken-bosses-audio")).isTrue();
    assertThat(Registry.isName("Questie/Questie")).isFalse();
    assertThat(Registry.isName("github:Questie/Questie")).isFalse();
    assertThat(Registry.isName("https://github.com/Questie/Questie")).isFalse();
    assertThat(Registry.isName("github.com/Questie/Questie")).isFalse();
  }

  @Test
  void anUnknownNameSuggestsCloseOnes() {
    assertThat(Registry.closest("questy")).containsExactly("questie");
    assertThat(Registry.closest("bosses")).containsExactly("spoken-bosses-audio");
    assertThatThrownBy(() -> Registry.require("questy"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("'questy' is not a well-known addon; did you mean questie?")
        .hasMessageContaining("owner/repo");
    assertThatThrownBy(() -> Registry.require("zzzzzzzz")).hasMessageNotContaining("did you mean");
  }

  @Test
  void distanceCountsEdits() {
    assertThat(Registry.distance("questie", "questie")).isEqualTo(0);
    assertThat(Registry.distance("questie", "questy")).isEqualTo(2);
    assertThat(Registry.distance("", "dbm")).isEqualTo(3);
  }
}
