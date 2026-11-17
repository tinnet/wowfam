package io.github.tinnet.wowfam.config;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class RegistryTest {

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
          assertThat(e.options().keySet()).isSubsetOf(Registry.OPTIONS);
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

  /** The table in docs/well-known-addons.md, which users read, lists exactly what add takes. */
  @Test
  void theDocsListEveryEntry() throws Exception {
    var documented =
        Files.readAllLines(Path.of("docs/well-known-addons.md")).stream()
            .filter(line -> line.startsWith("| `"))
            .map(line ->
                Arrays.stream(line.substring(1, line.lastIndexOf('|')).split("\\|"))
                    .map(String::strip)
                    .collect(joining(" | "))
                    .strip())
            .toList();
    var expected =
        Registry.all().stream()
            .map(e ->
                "`%s` | `%s` | %s"
                    .formatted(
                        e.name(),
                        e.source(),
                        Registry.OPTIONS.stream()
                            .filter(e.options()::containsKey)
                            .map(k -> "`%s = \"%s\"`".formatted(k, e.options().get(k)))
                            .collect(joining(", ")))
                    .strip())
            .toList();
    assertThat(documented).containsExactlyElementsOf(expected);
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
        .hasMessage(
            "'questy' is not a well-known addon. Did you mean questie? Give owner/repo or a"
                + " GitHub URL for addons not in the list.");
    assertThatThrownBy(() -> Registry.require("zzzzzzzz"))
        .hasMessage(
            "'zzzzzzzz' is not a well-known addon. Give owner/repo or a GitHub URL for addons not"
                + " in the list.");
  }

  @Test
  void distanceCountsEdits() {
    assertThat(Registry.distance("questie", "questie")).isEqualTo(0);
    assertThat(Registry.distance("questie", "questy")).isEqualTo(2);
    assertThat(Registry.distance("", "dbm")).isEqualTo(3);
  }
}
