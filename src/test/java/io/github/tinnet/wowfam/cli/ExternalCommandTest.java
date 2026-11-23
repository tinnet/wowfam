package io.github.tinnet.wowfam.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ExternalCommandTest {

  @Test
  void groupsFoldersIntoAddonsByName() {
    var folders =
        List.of(
            "BigWigs",
            "BigWigs_Core",
            "BigWigs_Plugins",
            "DBM-Core",
            "DBM-Core_Options",
            "DBM-StatusBarTimers",
            "Details",
            "ElvUI",
            "ElvUI_Options",
            "WeakAuras",
            "WeakAurasOptions");

    assertThat(ExternalCommand.groups(folders))
        .containsExactly(
            Map.entry("BigWigs", List.of("BigWigs", "BigWigs_Core", "BigWigs_Plugins")),
            Map.entry("DBM", List.of("DBM-Core", "DBM-Core_Options", "DBM-StatusBarTimers")),
            Map.entry("Details", List.of("Details")),
            Map.entry("ElvUI", List.of("ElvUI", "ElvUI_Options")),
            Map.entry("WeakAuras", List.of("WeakAuras", "WeakAurasOptions")));
  }

  @HegelTest
  void everyFolderIsInExactlyOneGroupInItsOrder(TestCase tc) {
    var folders =
        tc
            .draw(
                Generators.lists(Generators.fromRegex("[AB_-]{1,5}").fullmatch(true)).maxSize(12),
                "folders")
            .stream()
            .distinct()
            .toList();

    var groups = ExternalCommand.groups(folders);

    assertThat(groups.values().stream().flatMap(List::stream))
        .containsExactlyInAnyOrderElementsOf(folders);
    for (var group : groups.values()) {
      assertThat(group).isSubsetOf(folders);
      assertThat(folders.stream().filter(group::contains).toList())
          .containsExactlyElementsOf(group);
    }
  }
}
