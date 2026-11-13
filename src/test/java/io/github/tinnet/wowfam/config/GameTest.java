package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.WowInstall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GameTest {

  @TempDir Path tmp;

  @Test
  void readsTheGameOfAnAddOnsFolderFromTheLauncherFiles() throws Exception {
    var wow =
        WowInstall.create(
            tmp,
            Map.of("_forever_", "wowf", "_retail_", "wow", "_classic_era_", "wow_classic_era"),
            """
            eu|1|3627b0|1.60.1.70938|wowf
            eu|0|aaaaaa|12.0.0.1|wow
            us|1|bbbbbb|12.1.5.71234|wow
            eu|1|cccccc|1.15.8.65300|wow_classic_era
            """);

    assertThat(Game.detect(wow.resolve("_forever_/Interface/AddOns")))
        .hasValue(new Game("wowf", "1.60.1.70938", 16001));
    assertThat(Game.detect(wow.resolve("_retail_/Interface/AddOns")))
        .hasValue(new Game("wow", "12.1.5.71234", 120105));
    assertThat(Game.detect(wow.resolve("_classic_era_/Interface/AddOns").resolve("../..")))
        .hasValueSatisfying(g -> assertThat(g.interfaceNumber()).isEqualTo(11508));
    assertThat(Game.detect(wow.resolve("_forever_/Interface/AddOns")))
        .get()
        .hasToString("wowf 1.60.1.70938 (interface 16001)");
  }

  @Test
  void anyOtherFolderHasNoGame() throws Exception {
    assertThat(Game.detect(tmp.resolve("AddOns"))).isEmpty();

    // A flavor folder whose product the launcher does not list, or no .build.info at all.
    var wow = WowInstall.create(tmp, Map.of("_ptr_", "wowt"), "eu|1|abc|1.60.1.70938|wowf\n");
    assertThat(Game.detect(wow.resolve("_ptr_/Interface/AddOns"))).isEmpty();
    Files.delete(wow.resolve(Game.BUILD_INFO));
    assertThat(Game.detect(wow.resolve("_ptr_/Interface/AddOns"))).isEmpty();
  }

  @Test
  void readsTheLaunchersTables() throws Exception {
    var file = tmp.resolve("t");
    Files.writeString(file, "﻿A!STRING:0|B!DEC:1\r\n## seqn = 1\r\nx|1\r\ny\r\n\r\n");
    assertThat(Game.readTable(file)).containsExactly(Map.of("A", "x", "B", "1"), Map.of("A", "y"));
    assertThat(Game.readTable(tmp.resolve("missing"))).isEmpty();
    assertThat(Game.interfaceOf("3.80.2.1")).hasValue(38002);
    assertThat(Game.interfaceOf("1.15")).isEmpty();
  }

  @Test
  void addonsInAGameFolderAreForItsInterfaceUnlessTheySayOtherwise() throws Exception {
    var wow = WowInstall.create(tmp, Map.of("_forever_", "wowf"), "eu|1|abc|1.60.1.70938|wowf\n");
    var file = wow.resolve("_forever_/wowfam.toml");
    var toml =
        """
        [settings]
        addons_dir = "Interface/AddOns"
        [addons.a]
        source = "github:o/a"
        [addons.b]
        source = "github:o/b"
        flavor = "classic"
        [addons.c]
        source = "github:o/c"
        interface = 11508
        """;
    var loader = new ConfigLoader(Map.of(), tmp);

    var config = loader.parse(file, toml);
    assertThat(config.settings().game())
        .hasValueSatisfying(g -> assertThat(g.product()).isEqualTo("wowf"));
    assertThat(interfaceOf(config, "a")).hasValue("16001");
    assertThat(interfaceOf(config, "b")).isEmpty();
    assertThat(interfaceOf(config, "c")).hasValue("11508");

    // --addons-dir elsewhere: no game, nothing added.
    var elsewhere = loader.parse(file, toml, tmp.resolve("AddOns"));
    assertThat(elsewhere.settings().game()).isEmpty();
    assertThat(interfaceOf(elsewhere, "a")).isEmpty();
  }

  private static java.util.Optional<String> interfaceOf(Config config, String addon) {
    return java.util.Optional.ofNullable(config.addons().get(addon))
        .flatMap(a -> a.option("interface"));
  }
}
