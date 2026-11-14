package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConfigLoaderTest {

  private static final Path HOME = Path.of("/home/tester");
  private static final Path FILE = Path.of("/games/wow/wowfam.toml");
  private final ConfigLoader loader = new ConfigLoader(Map.of(), HOME);

  @Test
  void parsesTheSpokenExample() {
    var config =
        loader.parse(
            FILE,
            """
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

    assertThat(config.settings().addonsDir())
        .isEqualTo(Path.of("/home/tester/wow/_classic_era_/Interface/AddOns"));
    assertThat(config.settings().cacheDir()).isEqualTo(Path.of("/home/tester/.cache/wowfam"));
    assertThat(config.lockFile()).isEqualTo(Path.of("/games/wow/wowfam.lock"));
    assertThat(config.addons()).containsOnlyKeys("spoken", "spoken-readables-audio");

    var spoken = config.addons().get("spoken");
    assertThat(spoken).isNotNull();
    assertThat(spoken.source()).isEqualTo(new Source("github", "rusty-key/spoken-wow"));
    assertThat(spoken.wantsLatest()).isTrue();
    assertThat(spoken.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip"));

    var audio = config.addons().get("spoken-readables-audio");
    assertThat(audio).isNotNull();
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
    var config =
        loader.parse(
            FILE,
            """
            [addons.a]
            source = "github:o/r"
            version = 3
            """);
    assertThat(config.addons()).extractingByKey("a").extracting(AddonSpec::version).isEqualTo("3");
  }

  @Test
  void rejectsNamesThatCouldLeaveAFolder() {
    for (var name : List.of("x/../../Saved", "my addon", "..", "a.b")) {
      assertThatThrownBy(
              () -> loader.parse(FILE, "[addons.\"" + name + "\"]\nsource = \"github:o/r\"\n"))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("may only use letters, digits, - and _");
    }
    assertThat(loader.parse(FILE, "[addons.Ok_name-2]\nsource = \"github:o/r\"\n").addons())
        .containsKey("Ok_name-2");
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
    assertThatThrownBy(
            () -> loader.parse(FILE, "[addons.a]\nsource = \"github:o/r\"\ndepends = [\"b\"]\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("depends on 'b'");
  }

  @Test
  void rejectsInvalidToml() {
    assertThatThrownBy(() -> loader.parse(FILE, "[addons.a\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("invalid TOML");
  }

  @Test
  void readsGlobalHooksThenTheAddonsOwn() {
    var config =
        loader.parse(
            FILE,
            """
            [hooks]
            pre_install = "./review.sh"
            post_install = ["echo one", "echo two"]

            [addons.spoken]
            source = "github:rusty-key/spoken-wow"
            hooks = { pre_install = "./spoken-only.sh" }

            [addons.plain]
            source = "github:someone/plain"
            """);

    assertThat(config.hooks())
        .isEqualTo(new Hooks(List.of("./review.sh"), List.of("echo one", "echo two")));
    var spoken = config.addons().get("spoken");
    assertThat(spoken).isNotNull();
    assertThat(spoken.options()).isEmpty();
    assertThat(config.hooksFor(spoken))
        .isEqualTo(
            new Hooks(List.of("./review.sh", "./spoken-only.sh"), List.of("echo one", "echo two")));
    var plain = config.addons().get("plain");
    assertThat(plain).isNotNull();
    assertThat(config.hooksFor(plain)).isEqualTo(config.hooks());
  }

  @Test
  void noHooksByDefault() {
    var config = loader.parse(FILE, "[addons.a]\nsource = \"github:o/r\"\n");
    assertThat(config.hooks()).isEqualTo(Hooks.NONE);
    assertThat(config.addons().get("a")).extracting(AddonSpec::hooks).isEqualTo(Hooks.NONE);
  }

  @Test
  void rejectsUnknownHooks() {
    assertThatThrownBy(() -> loader.parse(FILE, "[hooks]\nbefore_install = \"x\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("unknown hook hooks.before_install");
  }

  @Test
  void rejectsHooksThatAreNotCommands() {
    assertThatThrownBy(() -> loader.parse(FILE, "[hooks]\npre_install = 3\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("hooks.pre_install must be a command or a list of commands");
  }
}
