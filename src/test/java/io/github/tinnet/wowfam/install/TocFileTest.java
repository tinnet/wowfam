package io.github.tinnet.wowfam.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TocFileTest {

  @Test
  void readsMetadataAndRequiredDependencies() {
    var toc =
        TocFile.parse(
            """
            ## Interface: 11509, 20506, 16001
            ## Title: |cffb48cffSpoken|r |cff6cb8ffQuests|r
            ## Version: 3.8.5
            ## Dependencies: Spoken
            ## RequiredDeps: LibStub, Spoken
            ## OptionalDeps: DialogueUI, Immersion
            # a comment
            Core.lua
            """);
    assertThat(toc.metadata())
        .containsEntry("Version", "3.8.5")
        .containsEntry("OptionalDeps", "DialogueUI, Immersion");
    assertThat(toc.requiredDependencies()).containsExactlyInAnyOrder("Spoken", "LibStub");
  }

  @Test
  void readsFlavorVariants(@TempDir Path tmp) throws Exception {
    var dir = Files.createDirectory(tmp.resolve("Spoken_Quests"));
    Files.writeString(dir.resolve("Spoken_Quests.toc"), "## Dependencies: Spoken\n");
    Files.writeString(
        dir.resolve("Spoken_Quests_Vanilla.toc"), "## Dependencies: Spoken, SpokenVanillaOnly\n");
    Files.writeString(dir.resolve("Unrelated.toc"), "## Dependencies: Nope\n");

    assertThat(TocFile.readAll(dir))
        .hasSize(2)
        .flatExtracting(t -> t.requiredDependencies().stream().toList())
        .containsOnly("Spoken", "SpokenVanillaOnly");
  }
}
