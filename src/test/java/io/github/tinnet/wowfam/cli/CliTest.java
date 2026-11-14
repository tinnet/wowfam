package io.github.tinnet.wowfam.cli;

import static io.github.tinnet.wowfam.FakeGitHub.addonZip;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.FakeGitHub;
import io.github.tinnet.wowfam.WowInstall;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Source;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** End to end through the CLI, against a fake GitHub laid out like rusty-key/spoken-wow. */
final class CliTest {

  private static final String REPO = "rusty-key/spoken-wow";

  @TempDir Path tmp;

  private final FakeGitHub github = new FakeGitHub();
  private Path config;
  private Path addons;
  private StringWriter out = new StringWriter();
  private StringWriter err = new StringWriter();

  @BeforeEach
  void setUp() throws Exception {
    addons = tmp.resolve("Interface/AddOns");
    config = tmp.resolve("wowfam.toml");
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"
        cache_dir = "cache"

        [addons.spoken]
        source = "github:rusty-key/spoken-wow"
        tag_prefix = "spoken/"
        asset = "Spoken-*.zip"

        [addons.spoken-readables-audio]
        source = "github:rusty-key/spoken-wow"
        tag_prefix = "readables-audio/"
        depends = ["spoken"]
        """);
    github
        .release(REPO, "spoken/v3.8.4", spoken("3.8.4", true))
        .release(
            REPO,
            "readables-audio/v2.3.0",
            addonZip(
                "SpokenReadablesAudio-2.3.0.zip", "2.3.0", Map.of("SpokenReadablesAudio", "")));
  }

  @AfterEach
  void tearDown() {
    github.close();
  }

  /**
   * The Spoken zip: the player plus its modules, which depend on it. 3.8.4 still ships a legacy
   * folder.
   */
  private static FakeGitHub.FakeAsset spoken(String version, boolean withLegacyFolder) {
    var folders = new LinkedHashMap<String, String>();
    folders.put("Spoken", "");
    folders.put("SpokenContributions", "");
    folders.put("Spoken_Quests", "Spoken");
    folders.put("Spoken_Readables", "Spoken");
    if (withLegacyFolder) {
      folders.put("SpokenQuests", "");
    }
    return addonZip("Spoken-" + version + ".zip", version, folders);
  }

  private int run(String... args) {
    out = new StringWriter();
    err = new StringWriter();
    var cl = WowfamCommand.newCommandLine(Map.of(), github::backends);
    cl.setOut(new PrintWriter(out, true));
    cl.setErr(new PrintWriter(err, true));
    var full = new String[args.length + 2];
    full[0] = "--config";
    full[1] = config.toString();
    System.arraycopy(args, 0, full, 2, args.length);
    return cl.execute(full);
  }

  @Test
  void installsUpdatesAndCleansUp() throws Exception {
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("✓ spoken 3.8.4", "✓ spoken-readables-audio 2.3.0");
    assertThat(addons.resolve("Spoken/Spoken.toc")).content().contains("## Version: 3.8.4");
    assertThat(addons.resolve("SpokenQuests")).isDirectory();
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();
    assertThat(err.toString()).isEmpty();

    var lock = LockFile.read(tmp.resolve("wowfam.lock"));
    assertThat(lock.get("spoken"))
        .hasValueSatisfying(e -> {
          assertThat(e.tag()).isEqualTo("spoken/v3.8.4");
          assertThat(e.folders())
              .containsExactly(
                  "Spoken",
                  "SpokenContributions",
                  "SpokenQuests",
                  "Spoken_Quests",
                  "Spoken_Readables");
        });

    // A second install is a no-op and downloads nothing.
    int downloads = downloads();
    assertThat(run("install")).isEqualTo(0);
    assertThat(out.toString()).contains("spoken 3.8.4 is up to date");
    assertThat(downloads()).isEqualTo(downloads);

    // A new release: install keeps the lock, update moves to it and drops the legacy folder.
    github.release(REPO, "spoken/v3.8.5", spoken("3.8.5", false));
    assertThat(run("outdated")).isEqualTo(0);
    assertThat(out.toString()).containsPattern("spoken\\s+3.8.4\\s+3.8.5");
    assertThat(run("install")).isEqualTo(0);
    assertThat(addons.resolve("Spoken/Spoken.toc")).content().contains("3.8.4");

    assertThat(run("update", "spoken")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("↑ spoken 3.8.4 -> 3.8.5");
    assertThat(addons.resolve("Spoken/Spoken.toc")).content().contains("3.8.5");
    assertThat(addons.resolve("SpokenQuests")).doesNotExist();
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();

    assertThat(run("list")).isEqualTo(0);
    assertThat(out.toString())
        .containsPattern("spoken\\s+latest\\s+3.8.5\\s+github:rusty-key/spoken-wow");
    assertThat(run("outdated")).isEqualTo(0);
    assertThat(out.toString()).contains("all addons are up to date");
  }

  @Test
  void installingOneAddonPullsInItsDependencies() {
    assertThat(run("install", "spoken-readables-audio")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("Spoken")).isDirectory();
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();
  }

  @Test
  void warnsAboutTocDependenciesNobodyInstalls() throws Exception {
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"
        cache_dir = "cache"

        [addons.quests-only]
        source = "github:someone/quests"
        """);
    github.release(
        "someone/quests",
        "v1.0.0",
        addonZip("Q-1.0.0.zip", "1.0.0", Map.of("Spoken_Quests", "Spoken")));
    assertThat(run("install")).isEqualTo(0);
    assertThat(err.toString())
        .contains("warning: Spoken_Quests requires Spoken, which is not installed");
  }

  @Test
  void refusesToTakeAnotherAddonsFolder() throws Exception {
    Files.writeString(
        config,
        Files.readString(config)
            + """

            [addons.copycat]
            source = "github:someone/copycat"
            """);
    github.release(
        "someone/copycat", "v1.0.0", addonZip("C-1.0.0.zip", "1.0.0", Map.of("Spoken", "")));
    assertThat(run("install")).isEqualTo(1);
    assertThat(err.toString())
        .contains("copycat wants to install folder Spoken, which belongs to spoken");
  }

  @Test
  void unknownVersionFailsWithAMessage() throws Exception {
    Files.writeString(
        config,
        Files.readString(config)
            .replace("asset = \"Spoken-*.zip\"", "asset = \"Spoken-*.zip\"\nversion = \"4\""));
    assertThat(run("install", "spoken")).isEqualTo(1);
    assertThat(err.toString()).contains("no release of spoken", "matches version '4'");
  }

  @Test
  void missingConfigFailsWithAMessage() throws Exception {
    Files.delete(config);
    assertThat(run("list")).isEqualTo(1);
    assertThat(err.toString()).contains("no wowfam.toml found");
  }

  @Test
  void installsTheZipForTheConfiguredFlavorAndKeepsItLocked() throws Exception {
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"
        cache_dir = "cache"

        [addons.bars]
        source = "github:someone/bars"
        flavor = "classic_era"
        """);
    github.release(
        "someone/bars",
        "v1.0.0",
        addonZip("Bars-v1.0.0.zip", "1.0.0-retail", Map.of("Bars", "")),
        addonZip("Bars-v1.0.0-classic.zip", "1.0.0-classic", Map.of("Bars", "")),
        FakeGitHub.releaseJson(
            Map.of(
                "Bars-v1.0.0.zip", List.of("mainline"),
                "Bars-v1.0.0-classic.zip", List.of("classic"))));

    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-classic");
    assertThat(LockFile.read(tmp.resolve("wowfam.lock")).get("bars"))
        .hasValueSatisfying(e -> {
          assertThat(e.asset()).isEqualTo("Bars-v1.0.0-classic.zip");
          assertThat(e.checksum()).isPresent();
        });

    // 2.0.0 dropped Classic: nothing to update to, and nothing reported as outdated.
    github.release(
        "someone/bars",
        "v2.0.0",
        addonZip("Bars-v2.0.0.zip", "2.0.0-retail", Map.of("Bars", "")),
        FakeGitHub.releaseJson(Map.of("Bars-v2.0.0.zip", List.of("mainline"))));
    assertThat(run("outdated")).isEqualTo(0);
    assertThat(out.toString()).contains("all addons are up to date");
    assertThat(run("update")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("bars 1.0.0 is up to date");
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-classic");
  }

  @Test
  void aFlavorNoReleaseHasFailsWithAMessage() throws Exception {
    Files.writeString(
        config,
        """
        [addons.bars]
        source = "github:someone/bars"
        flavor = "cata"
        """);
    github.release(
        "someone/bars",
        "v1.0.0",
        addonZip("Bars-v1.0.0.zip", "1.0.0", Map.of("Bars", "")),
        FakeGitHub.releaseJson(Map.of("Bars-v1.0.0.zip", List.of("mainline"))));

    assertThat(run("install")).isEqualTo(1);
    assertThat(err.toString())
        .contains("no release of bars", "matches version 'latest' for flavor 'cata'");
  }

  @Test
  void anInstalledAddonStaysWhenNoReleaseIsForTheGameAnyMore() throws Exception {
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"
        cache_dir = "cache"

        [addons.bars]
        source = "github:someone/bars"
        interface = "11508"
        """);
    github.release(
        "someone/bars",
        "v1.0.0",
        addonZip("Bars-v1.0.0.zip", "1.0.0", Map.of("Bars", "")),
        FakeGitHub.releaseJson(Map.of("Bars-v1.0.0.zip", List.of("classic"))));
    assertThat(run("install")).as(err.toString()).isEqualTo(0);

    // The game moves to another expansion the addon has no zip for.
    Files.writeString(config, Files.readString(config).replace("11508", "50503"));
    assertThat(run("update")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("no release of bars is for interface 50503; keeping 1.0.0");
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0");

    // A version no release has is a mistake in wowfam.toml, not the game's doing.
    var keeping = Files.readString(config);
    Files.writeString(config, keeping.replace("interface", "version = \"9\"\ninterface"));
    assertThat(run("update")).isEqualTo(1);
    assertThat(err.toString()).contains("matches version '9'");
    Files.writeString(config, keeping);

    // Not installed, there is nothing to keep: that is still an error.
    Files.writeString(config, Files.readString(config).replace("[addons.bars]", "[addons.bars2]"));
    assertThat(run("install", "bars2")).isEqualTo(1);
    assertThat(err.toString()).contains("matches version 'latest' for interface 50503");
  }

  private void withHooks(String hooks) throws Exception {
    Files.writeString(
        config, Files.readString(config).replace("[addons.spoken]", hooks + "\n[addons.spoken]"));
  }

  @DisabledOnOs(OS.WINDOWS)
  @Test
  void hooksRunAroundTheInstallWithItsDetails() throws Exception {
    withHooks(
        """
        [hooks]
        pre_install = 'test -f "$WOWFAM_DIR/Spoken/Spoken.toc" && test ! -e "$WOWFAM_ADDONS_DIR/Spoken" && echo "pre $WOWFAM_ADDON $WOWFAM_VERSION $WOWFAM_TAG" >> hooks.log'
        post_install = 'test -f "$WOWFAM_DIR/Spoken/Spoken.toc" && echo "post $WOWFAM_ADDON $(echo $WOWFAM_FOLDERS)" >> hooks.log'
        """);

    assertThat(run("install", "spoken")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("running pre_install hook for spoken");
    assertThat(tmp.resolve("hooks.log"))
        .content()
        .isEqualTo(
            """
            pre spoken 3.8.4 spoken/v3.8.4
            post spoken Spoken SpokenContributions SpokenQuests Spoken_Quests Spoken_Readables
            """);

    // Nothing is copied when the addon is up to date, so no hooks run.
    assertThat(run("install", "spoken")).isEqualTo(0);
    assertThat(tmp.resolve("hooks.log")).content().hasLineCount(2);
  }

  @DisabledOnOs(OS.WINDOWS)
  @Test
  void aFailingPreInstallHookKeepsTheAddonOut() throws Exception {
    withHooks(
        """
        [hooks]
        pre_install = 'echo "rejected $WOWFAM_ADDON"; exit 3'
        post_install = 'touch post-ran'
        """);

    assertThat(run("install")).isEqualTo(1);
    assertThat(out.toString()).contains("rejected spoken");
    assertThat(err.toString()).contains("pre_install hook for spoken failed with exit code 3");
    assertThat(addons).isEmptyDirectory();
    assertThat(tmp.resolve("post-ran")).doesNotExist();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock")).get("spoken")).isEmpty();

    assertThat(run("install", "--no-hooks")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("Spoken")).isDirectory();
    assertThat(tmp.resolve("post-ran")).doesNotExist();
  }

  @DisabledOnOs(OS.WINDOWS)
  @Test
  void aFailingPostInstallHookFailsTheRunButKeepsTheInstall() throws Exception {
    withHooks(
        """
        [hooks]
        post_install = 'exit 1'
        """);

    assertThat(run("install", "spoken")).isEqualTo(1);
    assertThat(err.toString()).contains("post_install hook for spoken failed with exit code 1");
    assertThat(addons.resolve("Spoken")).isDirectory();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock")).get("spoken")).isPresent();
  }

  private int downloads() {
    return (int) github.requests().stream().filter(r -> r.contains("/download/")).count();
  }

  @Test
  void addWorksOutTheEntryAppendsItAndInstalls() throws Exception {
    var before = Files.readString(config);
    github
        .release(
            "fitzforge/polestar",
            "client-v0.1.7",
            new FakeGitHub.FakeAsset("Polestar.Client.dmg", new byte[1]))
        .release(
            "fitzforge/polestar",
            "v0.3.7",
            addonZip("Polestar-0.3.7.zip", "0.3.7", Map.of("Polestar", "", "Polestar_Codex", "")),
            addonZip("Polestar_Recorder-0.3.7.zip", "0.3.7", Map.of("Polestar_Recorder", "")));
    Files.createDirectories(addons.resolve("Polestar"));

    assertThat(run("add", "--dry-run", "https://github.com/fitzforge/polestar")).isEqualTo(0);
    assertThat(out.toString()).contains("asset = \"Polestar-*.zip\"", "would install 0.3.7");
    assertThat(config).content().isEqualTo(before);

    assertThat(run("add", "https://github.com/fitzforge/polestar")).as(err.toString()).isEqualTo(0);
    var table =
        """
        [addons.polestar]
        source = "github:fitzforge/polestar"
        version = "latest"
        asset = "Polestar-*.zip"
        """;
    assertThat(config).content().isEqualTo("%s\n%s", before, table);
    assertThat(out.toString())
        .contains(table, "✓ polestar 0.3.7  (Polestar, Polestar_Codex)")
        .contains("Polestar was already in AddOns; wowfam manages it now")
        .doesNotContain("Polestar_Codex was already");
    assertThat(addons.resolve("Polestar/Polestar.toc")).exists();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock")).get("polestar")).isPresent();

    assertThat(run("add", "fitzforge/polestar", "--name", "again")).isEqualTo(1);
    assertThat(err.toString())
        .contains("github:fitzforge/polestar is already in", "as addons.polestar");
    assertThat(run("add", "fitzforge/polestar")).isEqualTo(1);
    assertThat(err.toString()).contains("github:fitzforge/polestar is already in");
    // GitHub names are case-insensitive.
    assertThat(run("add", "FitzForge/Polestar")).isEqualTo(1);
    assertThat(err.toString()).contains("is already in", "as addons.polestar");
    assertThat(run("add", "o/other", "--name", "polestar")).isEqualTo(1);
    assertThat(err.toString()).contains("addons.polestar is already in", "pick another --name");
  }

  @Test
  void addTakesTheUsersOptionsAndCanSkipTheInstall() throws Exception {
    github.release(
        REPO,
        "bosses-audio/v2.1.0",
        addonZip("SpokenBossesAudio-2.1.0.zip", "2.1.0", Map.of("SpokenBossesAudio", "")));
    assertThat(run("add", REPO, "--tag-prefix", "readables-audio/")).isEqualTo(1);
    assertThat(err.toString()).contains("already in", "as addons.spoken-readables-audio");

    assertThat(
            run(
                "add",
                "github:rusty-key/spoken-wow",
                "--name",
                "audio",
                "--tag-prefix",
                "bosses-audio/",
                "--version",
                "2",
                "--no-install"))
        .as(err.toString())
        .isEqualTo(0);
    var audio = new ConfigLoader(Map.of(), tmp).load(config).addons().get("audio");
    assertThat(audio).isNotNull();
    assertThat(audio.version()).isEqualTo("2");
    assertThat(audio.options())
        .containsEntry("tag_prefix", "bosses-audio/")
        .containsEntry("asset", "SpokenBossesAudio-*.zip");
    assertThat(addons).doesNotExist();
  }

  @Test
  void addTakesTheEntryOutAgainWhenTheInstallFails() throws Exception {
    // An earlier addon of the same name, taken out of wowfam.toml but still in the lock, must not
    // make the failed add look installed.
    var original = Files.readString(config);
    github.release("o/old", "v1.0", addonZip("Old-1.0.zip", "1.0", Map.of("Old", "")));
    assertThat(run("add", "o/old", "--name", "thing")).as(err.toString()).isEqualTo(0);
    Files.writeString(config, original);
    github.release("o/thing", "v1.0", addonZip("Thing-1.0.zip", "1.0", Map.of("Thing", "")));

    withHooks("[hooks]\npre_install = 'exit 3'");
    var before = Files.readString(config);
    assertThat(run("add", "o/thing")).isEqualTo(1);
    assertThat(err.toString())
        .contains("not installed, so not added to", "pre_install hook for thing failed");
    assertThat(config).content().isEqualTo(before);
  }

  @Test
  void addRejectsABadNameBeforeAskingGitHub() {
    int requests = github.requests().size();
    assertThat(run("add", "o/thing", "--name", "my addon")).isEqualTo(1);
    assertThat(err.toString()).contains("'my addon' cannot be an addon name");
    assertThat(github.requests()).hasSize(requests);
  }

  @Test
  void addNamesTheEntryAfterTheRepository() {
    assertThat(AddCommand.defaultName(Source.parse("github:mooreatv/BetterVendorPrice")))
        .isEqualTo("better-vendor-price");
    assertThat(AddCommand.defaultName(Source.parse("github:rusty-key/spoken-wow")))
        .isEqualTo("spoken-wow");
    assertThat(AddCommand.defaultName(Source.parse("github:o/Questie_2"))).isEqualTo("questie-2");
  }

  @Test
  void inAGameFolderReleaseJsonPicksTheZipForThatGame() throws Exception {
    var wow = WowInstall.create(tmp, Map.of("_forever_", "wowf"), "eu|1|abc|1.60.1.70938|wowf\n");
    config = wow.resolve("_forever_/wowfam.toml");
    addons = wow.resolve("_forever_/Interface/AddOns");
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"
        cache_dir = "cache"
        """);
    github.release(
        "someone/bars",
        "v1.0.0",
        addonZip("Bars-v1.0.0.zip", "1.0.0-retail", Map.of("Bars", "")),
        addonZip("Bars-v1.0.0-forever.zip", "1.0.0-forever", Map.of("Bars", "")),
        FakeGitHub.releaseJson(
            Map.of(
                "Bars-v1.0.0.zip", List.of("mainline"),
                "Bars-v1.0.0-forever.zip", List.of("forever"))));

    assertThat(run("add", "someone/bars")).as(err.toString()).isEqualTo(0);
    // Nothing to write down: the game folder says which zip, every time.
    assertThat(config)
        .content()
        .endsWith(
            """
            [addons.bars]
            source = "github:someone/bars"
            version = "latest"
            """);
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-forever");

    assertThat(run("list")).isEqualTo(0);
    assertThat(out.toString()).startsWith("game: wowf 1.60.1.70938 (interface 16001)");

    // An entry written by hand with an asset is the same addon.
    Files.writeString(
        config,
        Files.readString(config).replace("[addons.bars]", "[addons.old]\nasset = \"Bars-*.zip\""));
    assertThat(run("add", "someone/bars", "--name", "again", "-n")).isEqualTo(1);
    assertThat(err.toString()).contains("is already in", "as addons.old");
  }
}
