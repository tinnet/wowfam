package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ConfigLoaderTest {

  private static final Path HOME = Path.of("/home/tester");
  private static final Path FILE = Path.of("/home/tester/.config/wowfam/wowfam.toml");
  private final ConfigLoader loader = new ConfigLoader(Map.of(), HOME);

  /** {@code toml} as the addons of one game, {@code g}, with its AddOns in {@code /wow}. */
  private Config game(String toml) {
    return loader.parse(FILE, "[games.g]\naddons_dir = \"/wow\"\n\n" + toml).game("g");
  }

  @Test
  void parsesGamesAndTheirAddons() {
    var workspace =
        loader.parse(
            FILE,
            """
            [settings]
            default_game = "classic_era"

            [games.classic_era]
            dir = "~/wow/_classic_era_"

            [games.classic_era.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            tag_prefix = "spoken/"
            asset = "Spoken-*.zip"

            [games.classic_era.addons.spoken-readables-audio]
            source = "github:rusty-key/spoken-wow"
            version = "2"
            tag_prefix = "readables-audio/"
            depends = ["spoken"]

            [games.retail]
            addons_dir = "/elsewhere/AddOns"

            [games.retail.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            """);

    assertThat(workspace.games()).containsOnlyKeys("classic_era", "retail");
    assertThat(workspace.defaultGame()).hasValue("classic_era");
    assertThat(workspace.lockFile()).isEqualTo(Path.of("/home/tester/.config/wowfam/wowfam.lock"));
    var config = workspace.game("classic_era");
    assertThat(config.settings().addonsDir())
        .isEqualTo(Path.of("/home/tester/wow/_classic_era_/Interface/AddOns"));
    assertThat(config.settings().gameDir()).hasValue(Path.of("/home/tester/wow/_classic_era_"));
    assertThat(config.settings().cacheDir()).isEqualTo(Path.of("/home/tester/.cache/wowfam"));
    assertThat(config.addons()).containsOnlyKeys("spoken", "spoken-readables-audio");
    assertThat(workspace.game("retail").settings().addonsDir())
        .isEqualTo(Path.of("/elsewhere/AddOns"));
    assertThat(workspace.game("retail").settings().gameDir()).isEmpty();

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
  void picksTheGameACommandWorksOn() {
    var two =
        loader.parse(FILE, "[games.a]\naddons_dir = \"/a\"\n[games.b]\naddons_dir = \"/b\"\n");
    assertThat(two.selected(Optional.empty())).extracting(Config::name).containsExactly("a", "b");
    assertThat(two.selected(Optional.of("b"))).extracting(Config::name).containsExactly("b");
    assertThat(two.one(Optional.of("b")).name()).isEqualTo("b");
    assertThatThrownBy(() -> two.one(Optional.empty()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("which game? pick one with --game (a, b)");
    assertThatThrownBy(() -> two.one(Optional.of("c")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("no game named 'c'");

    var withDefault =
        loader.parse(
            FILE,
            """
            [settings]
            default_game = "b"
            [games.a]
            addons_dir = "/a"
            [games.b]
            addons_dir = "/b"
            """);
    assertThat(withDefault.one(Optional.empty()).name()).isEqualTo("b");
    assertThat(withDefault.selected(Optional.empty())).hasSize(2);

    var one = loader.parse(FILE, "[games.a]\naddons_dir = \"/a\"\n");
    assertThat(one.one(Optional.empty()).name()).isEqualTo("a");

    assertThatThrownBy(() -> loader.parse(FILE, "").one(Optional.empty()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("run wowfam init");
  }

  @Test
  void addonsDirOptionGoesToTheGameTheCommandWorksOn() {
    var toml = "[games.a]\ndir = \"/wow/_a_\"\n[games.b]\ndir = \"/wow/_b_\"\n";
    var workspace = loader.parse(FILE, toml, Optional.of("b"), Path.of("/tmp/AddOns"));
    assertThat(workspace.game("b").settings().addonsDir()).isEqualTo(Path.of("/tmp/AddOns"));
    assertThat(workspace.game("a").settings().addonsDir())
        .isEqualTo(Path.of("/wow/_a_/Interface/AddOns"));
    assertThatThrownBy(() -> loader.parse(FILE, toml, Optional.empty(), Path.of("/tmp/AddOns")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("--addons-dir needs --game");
  }

  @Test
  void anOldSingleGameFileSaysHowToMoveIt() {
    for (var toml :
        List.of("[addons.a]\nsource = \"github:o/a\"\n", "[settings]\naddons_dir = \"AddOns\"\n")) {
      assertThatThrownBy(() -> loader.parse(FILE, toml))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("wowfam init --import");
    }
  }

  @Test
  void aGameNeedsOneFolder() {
    assertThatThrownBy(() -> loader.parse(FILE, "[games.a]\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("games.a in", "needs either dir");
    assertThatThrownBy(
            () -> loader.parse(FILE, "[games.a]\ndir = \"/a\"\naddons_dir = \"/a/AddOns\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("needs either dir");
  }

  @Test
  void rejectsWhatLooksLikeAMisplacedTable() {
    assertThatThrownBy(() ->
            loader.parse(
                FILE, "[games.a]\ndir = \"/a\"\n[games.a.questie]\nsource = \"github:o/q\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("unknown key games.a.questie", "[games.a.addons.<name>]");
    assertThatThrownBy(() -> loader.parse(FILE, "[setings]\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("unknown table 'setings'");
    assertThatThrownBy(() -> loader.parse(FILE, "[settings]\ncache = \"x\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("unknown setting settings.cache");
    assertThatThrownBy(
            () -> loader.parse(FILE, "[settings]\ndefault_game = \"b\"\n[games.a]\ndir = \"/a\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("default_game is 'b'");
  }

  @Test
  void honoursXdgCache() {
    var workspace =
        new ConfigLoader(Map.of("XDG_CACHE_HOME", "/xdg"), HOME)
            .parse(FILE, "[games.a]\naddons_dir = \"/a\"\n");
    assertThat(workspace.game("a").settings().cacheDir()).isEqualTo(Path.of("/xdg/wowfam"));
    assertThat(workspace.game("a").addons()).isEmpty();
  }

  @Test
  void numericVersionsAreAccepted() {
    var config =
        game(
            """
            [games.g.addons.a]
            source = "github:o/r"
            version = 3
            """);
    assertThat(config.addons()).extractingByKey("a").extracting(AddonSpec::version).isEqualTo("3");
  }

  @Test
  void rejectsNamesThatCouldLeaveAFolder() {
    for (var name : List.of("x/../../Saved", "my addon", "..", "a.b")) {
      assertThatThrownBy(() -> game("[games.g.addons.\"" + name + "\"]\nsource = \"github:o/r\"\n"))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("may only use letters, digits, - and _");
      assertThatThrownBy(() -> loader.parse(FILE, "[games.\"" + name + "\"]\ndir = \"/a\"\n"))
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining("game names may only use letters, digits, - and _");
    }
    assertThat(game("[games.g.addons.Ok_name-2]\nsource = \"github:o/r\"\n").addons())
        .containsKey("Ok_name-2");
  }

  @Test
  void rejectsMissingSource() {
    assertThatThrownBy(() -> game("[games.g.addons.a]\nversion = \"1\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("games.g.addons.a needs a source");
  }

  @Test
  void rejectsSourceWithoutBackend() {
    assertThatThrownBy(() -> game("[games.g.addons.a]\nsource = \"rusty-key/spoken-wow\"\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("expected <backend>:<reference>");
  }

  @Test
  void rejectsUnknownDependencies() {
    assertThatThrownBy(
            () -> game("[games.g.addons.a]\nsource = \"github:o/r\"\ndepends = [\"b\"]\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("depends on 'b', which is not an addon of game g");
  }

  @Test
  void rejectsInvalidToml() {
    assertThatThrownBy(() -> loader.parse(FILE, "[games.a\n"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("invalid TOML");
  }

  @Test
  void readsGlobalHooksThenTheAddonsOwn() {
    var config =
        game(
            """
            [games.g.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            hooks = { pre_install = "./spoken-only.sh" }

            [games.g.addons.plain]
            source = "github:someone/plain"

            [hooks]
            pre_install = "./review.sh"
            post_install = ["echo one", "echo two"]
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
    var config = game("[games.g.addons.a]\nsource = \"github:o/r\"\n");
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
