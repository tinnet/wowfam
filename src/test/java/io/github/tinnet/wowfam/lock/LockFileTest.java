package io.github.tinnet.wowfam.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import io.github.tinnet.wowfam.util.Checksum;
import java.nio.file.Files;
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
            Optional.of(Checksum.sha256("ab".repeat(32))),
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

  @Test
  void writesTheChecksumWithItsAlgorithm(@TempDir Path tmp) {
    var lock = LockFile.read(tmp.resolve("wowfam.lock"));
    lock.put(
        "a",
        new LockEntry(
            "github:o/r",
            "1.0.0",
            "v1.0.0",
            "A.zip",
            "https://x/A.zip",
            Optional.of(new Checksum("sha1", "ABCDEF")),
            List.of("A")));
    assertThat(lock.toToml()).contains("checksum = \"sha1:abcdef\"\n").doesNotContain("sha256");
  }

  @Test
  void upgradesALockFromBeforeTheAlgorithmWasRecorded(@TempDir Path tmp) throws Exception {
    var path = tmp.resolve("wowfam.lock");
    Files.writeString(
        path,
        """
        [addons.spoken]
        source = "github:rusty-key/spoken-wow"
        version = "3.8.5"
        tag = "spoken/v3.8.5"
        asset = "Spoken-3.8.5.zip"
        url = "https://example.invalid/Spoken-3.8.5.zip"
        sha256 = "%s"
        folders = ["Spoken"]

        [addons.undigested]
        source = "github:o/r"
        version = "1.0.0"
        tag = "v1.0.0"
        asset = "A.zip"
        url = "https://example.invalid/A.zip"
        folders = ["A"]
        """
            .formatted("ab".repeat(32)));

    var lock = LockFile.read(path);

    assertThat(lock.get("spoken"))
        .hasValueSatisfying(
            e -> assertThat(e.checksum()).hasValue(Checksum.sha256("ab".repeat(32))));
    assertThat(lock.get("undigested")).hasValueSatisfying(e -> assertThat(e.checksum()).isEmpty());
    // Reading is enough to rewrite the file in the current format.
    assertThat(path)
        .content()
        .contains("checksum = \"sha256:" + "ab".repeat(32) + "\"")
        .doesNotContain("sha256 =")
        .isEqualTo(lock.toToml());
  }

  @Test
  void leavesALockInTheCurrentFormatAlone(@TempDir Path tmp) throws Exception {
    var path = tmp.resolve("wowfam.lock");
    var text =
        """
        # hand-edited, and not how wowfam would lay it out
        [addons.a]
        folders = ["A"]
        checksum = "sha256:%s"
        source = "github:o/r"
        version = "1.0.0"
        tag = "v1.0.0"
        asset = "A.zip"
        url = "https://example.invalid/A.zip"
        """
            .formatted("cd".repeat(32));
    Files.writeString(path, text);

    assertThat(LockFile.read(path).get("a"))
        .hasValueSatisfying(
            e -> assertThat(e.checksum()).hasValue(Checksum.sha256("cd".repeat(32))));
    assertThat(path).hasContent(text);
  }

  @Test
  void rejectsAChecksumWithoutAnAlgorithm() {
    assertThatThrownBy(() ->
            LockFile.parse(
                Path.of("wowfam.lock"),
                """
                [addons.a]
                source = "github:o/r"
                checksum = "abcdef"
                """))
        .isInstanceOf(LockFileException.class)
        .hasMessageContaining("checksum of a is not <algorithm>:<hex>");
  }

  private static dev.hegel.Generator<Checksum> checksums() {
    return Generators.sampledFrom(List.of("sha256", "sha1", "md5"))
        .flatMap(algorithm ->
            Generators.fromRegex("[0-9a-f]{1,64}")
                .fullmatch(true)
                .map(hex -> new Checksum(algorithm, hex)));
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
            tc.draw(Generators.optional(checksums()), "checksum"),
            tc.draw(Generators.lists(text).maxSize(3), "folders"));
    var lock = LockFile.parse(Path.of("wowfam.lock"), "");
    lock.put(name, entry);

    assertThat(LockFile.parse(Path.of("wowfam.lock"), lock.toToml()).get(name)).hasValue(entry);
  }
}
