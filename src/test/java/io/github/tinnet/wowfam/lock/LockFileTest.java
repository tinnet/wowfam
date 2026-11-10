package io.github.tinnet.wowfam.lock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LockFileTest {

  @Test
  void roundTripsThroughToml(@TempDir Path tmp) {
    var path = tmp.resolve("wowfam.lock");
    var lock = LockFile.read(path);
    assertThat(lock.entries()).isEmpty();

    var spoken =
        new LockEntry(
            "github:rusty-key/spoken-wow",
            "3.8.5",
            "spoken/v3.8.5",
            "Spoken-3.8.5.zip",
            "https://example.invalid/Spoken-3.8.5.zip",
            Optional.of("ab".repeat(32)),
            List.of("Spoken", "SpokenContributions", "Spoken_Quests"));
    var audio =
        new LockEntry(
            "github:rusty-key/spoken-wow",
            "2.3.0",
            "readables-audio/v2.3.0",
            "SpokenReadablesAudio-2.3.0.zip",
            "https://example.invalid/a.zip",
            Optional.empty(),
            List.of("SpokenReadablesAudio"));
    lock.put("spoken", spoken);
    lock.put("spoken-readables-audio", audio);
    lock.write();

    var reread = LockFile.read(path);
    assertThat(reread.entries())
        .containsEntry("spoken", spoken)
        .containsEntry("spoken-readables-audio", audio);
    assertThat(reread.ownerOf("Spoken_Quests")).hasValue("spoken");
    assertThat(reread.ownerOf("Unknown")).isEmpty();
  }

  @Test
  void writesOneTablePerAddon(@TempDir Path tmp) {
    var lock = LockFile.read(tmp.resolve("wowfam.lock"));
    lock.put(
        "spoken",
        new LockEntry(
            "github:o/r",
            "1.0.0",
            "v1.0.0",
            "A.zip",
            "https://x/A.zip",
            Optional.empty(),
            List.of("A")));
    assertThat(lock.toToml())
        .contains("[addons.spoken]\nsource = \"github:o/r\"\n")
        .contains("folders = [\"A\"]");
  }

  /** Any text survives a write and a read: names, versions and folders are not ours to choose. */
  @HegelTest
  void anyStringsRoundTrip(TestCase tc) {
    var text = Generators.text().maxSize(15);
    var name = tc.draw(text.minSize(1), "name");
    var entry =
        new LockEntry(
            tc.draw(text, "source"),
            tc.draw(text, "version"),
            tc.draw(text, "tag"),
            tc.draw(text, "asset"),
            tc.draw(text, "url"),
            tc.draw(Generators.optional(text), "sha256"),
            tc.draw(Generators.lists(text).maxSize(3), "folders"));
    var lock = LockFile.parse(Path.of("wowfam.lock"), "");
    lock.put(name, entry);

    assertThat(LockFile.parse(Path.of("wowfam.lock"), lock.toToml()).get(name)).hasValue(entry);
  }
}
