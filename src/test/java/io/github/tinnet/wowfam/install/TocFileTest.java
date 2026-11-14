package io.github.tinnet.wowfam.install;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
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

  @Test
  void readsLatin1AndByteOrderMarks(@TempDir Path tmp) throws Exception {
    var dir = Files.createDirectory(tmp.resolve("Old"));
    // Latin-1, which isn't valid UTF-8, starting with a UTF-8 byte-order mark.
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    bytes.write("## Dependencies: Lib\n## Title-deDE: \u00dcber\n".getBytes(ISO_8859_1));
    Files.write(dir.resolve("Old.toc"), bytes.toByteArray());

    assertThat(TocFile.readAll(dir))
        .singleElement()
        .satisfies(t -> assertThat(t.requiredDependencies()).containsExactly("Lib"));
  }
}
