package io.github.tinnet.wowfam.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.WowInstall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WowInstallsTest {

  @TempDir Path home;

  private static final String ROWS = "eu|1|a|12.0.1.1|wow\n";

  private Path install(Path prefix) throws Exception {
    return WowInstall.create(
        prefix.resolve("drive_c/Program Files (x86)"), Map.of("_retail_", "wow"), ROWS);
  }

  @Test
  void findsInstallsInTheWinePrefixesOfLinuxLaunchers() throws Exception {
    var steam = install(home.resolve(".local/share/Steam/steamapps/compatdata/123456/pfx"));
    var flatpakBottles =
        install(home.resolve(".var/app/com.usebottles.bottles/data/bottles/bottles/Battle.net"));
    var heroic = install(home.resolve("Games/Heroic/Prefixes/default/Battle.net"));

    var found = WowInstalls.find("Linux", Map.of(), home);

    assertThat(found)
        .extracting(WowInstalls.Found::dir)
        .containsExactlyInAnyOrder(
            steam.resolve("_retail_"),
            flatpakBottles.resolve("_retail_"),
            heroic.resolve("_retail_"));
    assertThat(found.getFirst().name()).isEqualTo("retail");
    assertThat(found.getFirst().game())
        .hasValueSatisfying(g -> assertThat(g.interfaceNumber()).isEqualTo(120001));
  }

  @Test
  void followsALutrisGameMovedElsewhere() throws Exception {
    var prefix = home.resolve("somewhere/else/bnet");
    var wow = install(prefix);
    var games = Files.createDirectories(home.resolve(".config/lutris/games"));
    Files.writeString(
        games.resolve("battlenet-123.yml"),
        """
        game:
          exe: drive_c/Program Files (x86)/Battle.net/Battle.net Launcher.exe
          prefix: ~/somewhere/else/bnet
        system: {}
        """);

    assertThat(WowInstalls.find("Linux", Map.of(), home))
        .extracting(WowInstalls.Found::dir)
        .containsExactly(wow.resolve("_retail_"));
  }

  @Test
  void aGameFolderOrItsAddOnsMeanThatGame() throws Exception {
    var wow =
        WowInstall.create(
            home, Map.of("_retail_", "wow", "_classic_era_", "wow_classic_era"), ROWS);

    assertThat(WowInstalls.gamesIn(wow))
        .extracting(WowInstalls.Found::name)
        .containsExactly("classic_era", "retail");
    assertThat(WowInstalls.gamesIn(wow.resolve("_retail_")))
        .extracting(WowInstalls.Found::name)
        .containsExactly("retail");
    assertThat(WowInstalls.gamesIn(wow.resolve("_retail_/Interface/AddOns")))
        .extracting(WowInstalls.Found::name)
        .containsExactly("retail");
    assertThat(WowInstalls.gamesIn(home.resolve("nothing"))).isEmpty();
  }

  @Test
  void namesGamesAfterTheirFolder() {
    assertThat(WowInstalls.gameName("_classic_era_")).isEqualTo("classic_era");
    assertThat(WowInstalls.gameName("_ptr_")).isEqualTo("ptr");
    assertThat(WowInstalls.gameName("_anniversary_")).isEqualTo("anniversary");
  }

  @Test
  void skipsTheFolderAnUninstalledGameLeftBehind() throws Exception {
    var wow = WowInstall.create(home, Map.of("_forever_", "wowf"), ROWS);
    Files.createDirectories(wow.resolve("_retail_/Interface"));

    assertThat(WowInstalls.gamesIn(wow))
        .extracting(WowInstalls.Found::name)
        .containsExactly("forever");
    // Unless it is pointed at.
    assertThat(WowInstalls.gamesIn(wow.resolve("_retail_")))
        .extracting(WowInstalls.Found::name)
        .containsExactly("retail");
  }
}
