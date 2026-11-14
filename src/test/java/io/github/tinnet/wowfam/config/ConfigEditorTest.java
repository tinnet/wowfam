package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class ConfigEditorTest {

  private static final Path FILE = Path.of("/game/wowfam.toml");

  private static Config parse(String toml) {
    return new ConfigLoader(Map.of(), Path.of("/home/tester")).parse(FILE, toml);
  }

  @Test
  void appendsATableAndLeavesTheRestOfTheFileAlone() {
    var original =
        """
        # my addons
        [settings]
        addons_dir = "Interface/AddOns"   # relative to this file

        [addons.spoken]
        source = "github:rusty-key/spoken-wow"
        tag_prefix = "spoken/\"\
        """;
    var spec =
        new AddonSpec(
            "polestar",
            Source.parse("github:fitzforge/polestar"),
            "latest",
            List.of(),
            Map.of("prerelease", "true", "asset", "Polestar-*.zip", "zz", "last"));

    var table = ConfigEditor.addonTable(spec);
    var edited = ConfigEditor.append(original, table);

    assertThat(table)
        .isEqualTo(
            """
            [addons.polestar]
            source = "github:fitzforge/polestar"
            version = "latest"
            asset = "Polestar-*.zip"
            prerelease = true
            zz = "last"
            """);
    assertThat(edited).isEqualTo("%s\n\n%s", original, table);
    assertThat(parse(edited).addons()).containsEntry("polestar", spec);
    assertThat(ConfigEditor.append(original + "\n\n", table))
        .isEqualTo("%s\n\n%s", original, table);
    assertThat(ConfigEditor.append("", table)).isEqualTo(table);
  }

  @Test
  void rejectsNamesThatAreNotBareKeys() {
    var spec = new AddonSpec("a b", Source.parse("github:o/r"), "latest", List.of(), Map.of());
    assertThatThrownBy(() -> ConfigEditor.addonTable(spec))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("cannot be an addon name");
  }

  @HegelTest
  void anyValueReadsBackAsWritten(TestCase tc) {
    var value = tc.draw(Generators.text(), "value");
    var spec =
        new AddonSpec(
            "x", Source.parse("github:o/r"), "latest", List.of(), Map.of("asset", value + "!"));

    var read = parse(ConfigEditor.append("", ConfigEditor.addonTable(spec))).addons().get("x");

    assertThat(read).isNotNull();
    assertThat(read.option("asset")).hasValue(value + "!");
  }

  @DisabledOnOs(OS.WINDOWS) // symlinks and POSIX permissions
  @Test
  void writesThroughASymlinkAndKeepsPermissions(@TempDir Path tmp) throws Exception {
    var real = Files.createDirectories(tmp.resolve("dotfiles")).resolve("wowfam.toml");
    Files.writeString(real, "# mine\n");
    Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-------"));
    var link = Files.createSymbolicLink(tmp.resolve("wowfam.toml"), real);

    ConfigEditor.write(link, "# changed\n");

    assertThat(link).isSymbolicLink();
    assertThat(real).content().isEqualTo("# changed\n");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(real)))
        .isEqualTo("rw-------");
    assertThat(tmp.resolve("dotfiles")).isDirectoryNotContaining("glob:**.tmp");
  }

  @Test
  void writesAFileThatIsNotThereYet(@TempDir Path tmp) {
    var file = tmp.resolve("wowfam.toml");
    ConfigEditor.write(file, "# new\n");
    assertThat(file).content().isEqualTo("# new\n");
  }
}
