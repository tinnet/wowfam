package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    private static final Path HOME = Path.of("/home/tester");
    private static final Path FILE = Path.of("/games/wow/wowfam.toml");
    private final ConfigLoader loader = new ConfigLoader(Map.of(), HOME);

    @Test
    void parsesTheSpokenExample() {
        var config = loader.parse(FILE, """
                [settings]
                addons_dir = "~/wow/_classic_era_/Interface/AddOns"

                [addons.spoken]
                source = "github:rusty-key/spoken-wow"
                tag_prefix = "spoken/"
                asset = "Spoken-*.zip"

                [addons.spoken-readables-audio]
                source = "github:rusty-key/spoken-wow"
                version = "2"
                tag_prefix = "readables-audio/"
                depends = ["spoken"]
                """);

        assertThat(config.settings().addonsDir()).isEqualTo(Path.of("/home/tester/wow/_classic_era_/Interface/AddOns"));
        assertThat(config.settings().cacheDir()).isEqualTo(Path.of("/home/tester/.cache/wowfam"));
        assertThat(config.lockFile()).isEqualTo(Path.of("/games/wow/wowfam.lock"));
        assertThat(config.addons()).containsOnlyKeys("spoken", "spoken-readables-audio");

        var spoken = config.addons().get("spoken");
        assertThat(spoken.source()).isEqualTo(new Source("github", "rusty-key/spoken-wow"));
        assertThat(spoken.wantsLatest()).isTrue();
        assertThat(spoken.options()).containsExactlyInAnyOrderEntriesOf(Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip"));

        var audio = config.addons().get("spoken-readables-audio");
        assertThat(audio.version()).isEqualTo("2");
        assertThat(audio.depends()).containsExactly("spoken");

        assertThat(config.installOrder(List.of("spoken-readables-audio")))
                .extracting(AddonSpec::name)
                .containsExactly("spoken", "spoken-readables-audio");
    }

    @Test
    void defaultsAddonsDirNextToTheFileAndHonoursXdgCache() {
        var config = new ConfigLoader(Map.of("XDG_CACHE_HOME", "/xdg"), HOME).parse(FILE, "");
        assertThat(config.settings().addonsDir()).isEqualTo(Path.of("/games/wow/AddOns"));
        assertThat(config.settings().cacheDir()).isEqualTo(Path.of("/xdg/wowfam"));
        assertThat(config.addons()).isEmpty();
    }

    @Test
    void numericVersionsAreAccepted() {
        var config = loader.parse(FILE, """
                [addons.a]
                source = "github:o/r"
                version = 3
                """);
        assertThat(config.addons().get("a").version()).isEqualTo("3");
    }

    @Test
    void rejectsMissingSource() {
        assertThatThrownBy(() -> loader.parse(FILE, "[addons.a]\nversion = \"1\"\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("addons.a needs a source");
    }

    @Test
    void rejectsSourceWithoutBackend() {
        assertThatThrownBy(() -> loader.parse(FILE, "[addons.a]\nsource = \"rusty-key/spoken-wow\"\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("expected <backend>:<reference>");
    }

    @Test
    void rejectsUnknownDependencies() {
        assertThatThrownBy(() -> loader.parse(FILE, "[addons.a]\nsource = \"github:o/r\"\ndepends = [\"b\"]\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("depends on 'b'");
    }

    @Test
    void rejectsInvalidToml() {
        assertThatThrownBy(() -> loader.parse(FILE, "[addons.a\n"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("invalid TOML");
    }
}
