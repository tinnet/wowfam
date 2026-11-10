package io.github.tinnet.wowfam.install;

import static io.github.tinnet.wowfam.FakeGitHub.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZipExtractorTest {

  @TempDir Path tmp;

  private Path write(String name, Map<String, String> entries) throws Exception {
    var file = tmp.resolve(name);
    Files.write(file, zip(name, entries).content());
    return file;
  }

  @Test
  void extractsEveryTopLevelFolder() throws Exception {
    var entries = new LinkedHashMap<String, String>();
    entries.put("Spoken/Spoken.toc", "## Version: 3.8.5\n");
    entries.put("Spoken/Core/Player.lua", "-- lua\n");
    entries.put("Spoken_Quests/Spoken_Quests.toc", "## Dependencies: Spoken\n");
    entries.put("__MACOSX/Spoken/._Spoken.toc", "junk");
    var out = Files.createDirectory(tmp.resolve("out"));

    var folders = ZipExtractor.extract(write("Spoken-3.8.5.zip", entries), out);

    assertThat(folders).containsExactly("Spoken", "Spoken_Quests");
    assertThat(out.resolve("Spoken/Core/Player.lua")).hasContent("-- lua");
    assertThat(out.resolve("__MACOSX")).doesNotExist();
  }

  @Test
  void refusesTopLevelFiles() throws Exception {
    var zip = write("loose.zip", Map.of("Spoken.toc", "## Title: x\n"));
    assertThatThrownBy(() -> ZipExtractor.extract(zip, tmp))
        .isInstanceOf(InstallException.class)
        .hasMessageContaining("file at its top level");
  }

  @Test
  void refusesZipSlip() throws Exception {
    var zip = write("evil.zip", Map.of("Spoken/../../evil.lua", "boom"));
    assertThatThrownBy(() -> ZipExtractor.extract(zip, tmp.resolve("out")))
        .isInstanceOf(InstallException.class)
        .hasMessageContaining("leaves the target folder");
    assertThat(tmp.resolveSibling("evil.lua")).doesNotExist();
  }

  /** Whatever an archive calls its entries, an accepted one lands inside the target folder. */
  @HegelTest
  void acceptedPathsNeverEscape(TestCase tc) {
    var segment =
        Generators.oneOf(
            Generators.sampledFrom("..", ".", "", "Spoken", "C:", "a\\..\\..", "/"),
            Generators.text().maxSize(6));
    var name =
        String.join(
            tc.draw(Generators.sampledFrom("/", "\\"), "separator"),
            tc.draw(Generators.lists(segment).minSize(1).maxSize(5), "segments"));
    var root = Path.of("/addons").toAbsolutePath();
    try {
      ZipExtractor.safeRelativePath(name)
          .ifPresent(parts -> {
            var target = root;
            for (var p : parts) {
              target = target.resolve(p);
            }
            assertThat(target.normalize()).startsWithRaw(root);
            assertThat(parts).doesNotContain("..", ".", "");
          });
    } catch (InstallException expected) {
      // rejected names are fine
    } catch (java.nio.file.InvalidPathException expected) {
      // the platform cannot represent it at all
    }
  }
}
