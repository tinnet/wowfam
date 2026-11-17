package io.github.tinnet.wowfam.cli;

import static io.github.tinnet.wowfam.FakeGitHub.addonZip;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.FakeGitHub;
import io.github.tinnet.wowfam.WowInstall;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Registry;
import io.github.tinnet.wowfam.config.Source;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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
        cache_dir = "cache"

        [games.test]
        addons_dir = "Interface/AddOns"

        [games.test.addons.spoken]
        source = "github:rusty-key/spoken-wow"
        tag_prefix = "spoken/"
        asset = "Spoken-*.zip"

        [games.test.addons.spoken-readables-audio]
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
    var full = new String[args.length + 2];
    full[0] = "--config";
    full[1] = config.toString();
    System.arraycopy(args, 0, full, 2, args.length);
    return runAs(Map.of(), Optional.empty(), full);
  }

  /**
   * The CLI as a user with {@code env} runs it, answering questions with {@code answers}. Always as
   * on Linux, where every place {@code init} looks is under {@code $HOME}: on macOS it would also
   * find a real {@code /Applications/World of Warcraft}.
   */
  private int runAs(Map<String, String> env, Optional<String> answers, String... args) {
    out = new StringWriter();
    err = new StringWriter();
    var cl =
        WowfamCommand.newCommandLine(
            env,
            "Linux",
            github::backends,
            answers.map(a -> new BufferedReader(new StringReader(a))));
    cl.setOut(new PrintWriter(out, true));
    cl.setErr(new PrintWriter(err, true));
    return cl.execute(args);
  }

  @Test
  void installsUpdatesAndCleansUp() throws Exception {
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("✓ spoken 3.8.4", "✓ spoken-readables-audio 2.3.0");
    assertThat(addons.resolve("Spoken/Spoken.toc")).content().contains("## Version: 3.8.4");
    assertThat(addons.resolve("SpokenQuests")).isDirectory();
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();
    assertThat(err.toString()).isEmpty();

    var lock = LockFile.read(tmp.resolve("wowfam.lock"), "test");
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
        cache_dir = "cache"

        [games.test]
        addons_dir = "Interface/AddOns"

        [games.test.addons.quests-only]
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

            [games.test.addons.copycat]
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
    assertThat(err.toString()).contains("no config at", "run wowfam init");
  }

  @Test
  void installsTheZipForTheConfiguredFlavorAndKeepsItLocked() throws Exception {
    Files.writeString(
        config,
        """
        [settings]
        cache_dir = "cache"

        [games.test]
        addons_dir = "Interface/AddOns"

        [games.test.addons.bars]
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
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").get("bars"))
        .hasValueSatisfying(e -> {
          assertThat(e.asset()).isEqualTo("Bars-v1.0.0-classic.zip");
          assertThat(e.checksum()).isPresent();
        });

    // Another flavor is another zip of the same release: install swaps it in.
    var classic = Files.readString(config);
    Files.writeString(config, classic.replace("classic_era", "retail"));
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-retail");
    Files.writeString(config, classic);
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-classic");

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
        [games.test]
        addons_dir = "Interface/AddOns"

        [games.test.addons.bars]
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
        cache_dir = "cache"

        [games.test]
        addons_dir = "Interface/AddOns"

        [games.test.addons.bars]
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
    Files.writeString(
        config,
        Files.readString(config).replace("[games.test.addons.bars]", "[games.test.addons.bars2]"));
    assertThat(run("install", "bars2")).isEqualTo(1);
    assertThat(err.toString()).contains("matches version 'latest' for interface 50503");
  }

  private void withHooks(String hooks) throws Exception {
    Files.writeString(
        config,
        Files.readString(config)
            .replace("[games.test.addons.spoken]", hooks + "\n[games.test.addons.spoken]"));
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
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").get("spoken")).isEmpty();

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
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").get("spoken")).isPresent();
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
        [games.test.addons.polestar]
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
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").get("polestar")).isPresent();

    assertThat(run("add", "fitzforge/polestar", "--name", "again")).isEqualTo(1);
    assertThat(err.toString()).contains("github:fitzforge/polestar is already in", "as polestar");
    assertThat(run("add", "fitzforge/polestar")).isEqualTo(1);
    assertThat(err.toString()).contains("github:fitzforge/polestar is already in");
    // GitHub names are case-insensitive.
    assertThat(run("add", "FitzForge/Polestar")).isEqualTo(1);
    assertThat(err.toString()).contains("is already in", "as polestar");
    assertThat(run("add", "o/other", "--name", "polestar")).isEqualTo(1);
    assertThat(err.toString())
        .contains("polestar is already an addon of game test", "pick another --name");
  }

  @Test
  void addTakesTheUsersOptionsAndCanSkipTheInstall() throws Exception {
    github.release(
        REPO,
        "bosses-audio/v2.1.0",
        addonZip("SpokenBossesAudio-2.1.0.zip", "2.1.0", Map.of("SpokenBossesAudio", "")));
    assertThat(run("add", REPO, "--tag-prefix", "readables-audio/")).isEqualTo(1);
    assertThat(err.toString()).contains("already in", "as spoken-readables-audio");

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
    var audio = new ConfigLoader(Map.of(), tmp).load(config).game("test").addons().get("audio");
    assertThat(audio).isNotNull();
    assertThat(audio.version()).isEqualTo("2");
    assertThat(audio.options())
        .containsEntry("tag_prefix", "bosses-audio/")
        .containsEntry("asset", "SpokenBossesAudio-*.zip");
    assertThat(addons).doesNotExist();
  }

  @Test
  void addTakesAWellKnownAddonByName() throws Exception {
    github.release(
        REPO,
        "bosses-audio/v2.1.0",
        addonZip("SpokenBossesAudio-2.1.0.zip", "2.1.0", Map.of("SpokenBossesAudio", "")));
    var before = Files.readString(config);

    assertThat(run("add", "Spoken-Bosses-Audio")).as(err.toString()).isEqualTo(0);
    // The entry is the resolved one, not the registry name.
    assertThat(config)
        .content()
        .isEqualTo(
            "%s\n%s",
            before,
            """
            [games.test.addons.spoken-bosses-audio]
            source = "github:rusty-key/spoken-wow"
            version = "latest"
            tag_prefix = "bosses-audio/"
            asset = "SpokenBossesAudio-*.zip"
            """);
    assertThat(addons.resolve("SpokenBossesAudio")).isDirectory();

    // The registry's spoken is the configured one.
    assertThat(run("add", "spoken", "--name", "again", "-n")).isEqualTo(1);
    assertThat(err.toString()).contains("github:rusty-key/spoken-wow is already in", "as spoken");
  }

  @Test
  void addTakesTheUsersOptionsOverTheRegistrys() throws Exception {
    github.release(
        "fitzforge/polestar",
        "v0.3.7",
        addonZip("Polestar-0.3.7.zip", "0.3.7", Map.of("Polestar", "")),
        addonZip("Polestar_Recorder-0.3.7.zip", "0.3.7", Map.of("Polestar_Recorder", "")));

    assertThat(run("add", "polestar", "--name", "rec", "--asset", "Polestar_Recorder-*.zip", "-n"))
        .as(err.toString())
        .isEqualTo(0);
    assertThat(out.toString())
        .contains("[games.test.addons.rec]", "asset = \"Polestar_Recorder-*.zip\"");

    // spoken's asset belongs with its tag prefix: another prefix takes neither.
    github.release(
        REPO,
        "bosses-audio/v2.1.0",
        addonZip("SpokenBossesAudio-2.1.0.zip", "2.1.0", Map.of("SpokenBossesAudio", "")));
    assertThat(run("add", "spoken", "--name", "bosses", "--tag-prefix", "bosses-audio/", "-n"))
        .as(err.toString())
        .isEqualTo(0);
    assertThat(out.toString())
        .contains("tag_prefix = \"bosses-audio/\"", "asset = \"SpokenBossesAudio-*.zip\"")
        .doesNotContain("Spoken-*.zip");
  }

  @Test
  void addSaysWhenANameIsNotWellKnown() {
    int requests = github.requests().size();
    assertThat(run("add", "questy")).isEqualTo(1);
    assertThat(err.toString())
        .contains("'questy' is not a well-known addon. Did you mean questie?")
        .doesNotContain("\tat ");
    assertThat(github.requests()).hasSize(requests);
  }

  /**
   * picocli formats help texts with String.format, so a bare % (as in %APPDATA%) makes it print
   * warnings, to System.err, with every --help.
   */
  @Test
  void helpPrintsNoPicocliWarnings() {
    var commands = new ArrayDeque<CommandLine>();
    commands.addLast(
        WowfamCommand.newCommandLine(Map.of(), "Linux", github::backends, Optional.empty()));
    var stderr = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(stderr, true, UTF_8));
    try {
      while (!commands.isEmpty()) {
        var cl = commands.removeFirst();
        commands.addAll(cl.getSubcommands().values());
        assertThat(cl.getUsageMessage()).isNotBlank();
      }
    } finally {
      System.setErr(original);
    }
    assertThat(stderr.toString(UTF_8)).doesNotContain("picocli WARN");
  }

  @Test
  void registryListsTheWellKnownAddonsWithoutAConfig() throws Exception {
    Files.delete(config);
    for (var args : List.of(List.of("registry"), List.of("registry", "ls"))) {
      assertThat(run(args.toArray(String[]::new))).as(err.toString()).isEqualTo(0);
      var lines = out.toString().lines().toList();
      assertThat(lines.getFirst()).startsWith("NAME").contains("SOURCE", "OPTIONS");
      assertThat(lines)
          .hasSize(Registry.all().size() + 1)
          .anySatisfy(l ->
              assertThat(l)
                  .startsWith("spoken ")
                  .contains("github:rusty-key/spoken-wow")
                  .endsWith("tag_prefix=spoken/ asset=Spoken-*.zip"))
          .anySatisfy(l -> assertThat(l).startsWith("weakauras ").endsWith("WeakAuras2"));
    }
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
  void everydayFailuresAreMessagesNotStackTraces() throws Exception {
    // A hand-edited lock with a typo.
    Files.writeString(tmp.resolve("wowfam.lock"), "[games.test.addons.spoken\n");
    assertThat(run("install")).isEqualTo(1);
    assertThat(err.toString()).startsWith("error: invalid lock file").doesNotContain("\tat ");
    Files.delete(tmp.resolve("wowfam.lock"));

    // Something other than GitHub answering, e.g. a captive portal.
    assertThat(run("add", FakeGitHub.CAPTIVE_PORTAL, "-n")).isEqualTo(1);
    assertThat(err.toString())
        .startsWith("error: listing releases of captive/portal got something other than")
        .doesNotContain("\tat ");
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
    addons = wow.resolve("_forever_/Interface/AddOns");
    Files.writeString(
        config,
        """
        [settings]
        cache_dir = "cache"

        [games.test]
        dir = "World of Warcraft/_forever_"
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
            [games.test.addons.bars]
            source = "github:someone/bars"
            version = "latest"
            """);
    assertThat(addons.resolve("Bars/Bars.toc")).content().contains("## Version: 1.0.0-forever");

    assertThat(run("list")).isEqualTo(0);
    assertThat(out.toString()).startsWith("test: wowf 1.60.1.70938 (interface 16001)");

    // An entry written by hand with an asset is the same addon.
    Files.writeString(
        config,
        Files.readString(config)
            .replace(
                "[games.test.addons.bars]", "[games.test.addons.old]\nasset = \"Bars-*.zip\""));
    assertThat(run("add", "someone/bars", "--name", "again", "-n")).isEqualTo(1);
    assertThat(err.toString()).contains("is already in", "as old");
  }

  @Test
  void removeUninstallsAnAddonAndTakesItOutOfTheConfig() throws Exception {
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    var before = Files.readString(config);

    assertThat(run("remove", "spoken")).isEqualTo(1);
    assertThat(err.toString())
        .contains("spoken-readables-audio depends on spoken; remove spoken-readables-audio too");

    assertThat(run("remove", "spoken-readables-audio", "--dry-run")).isEqualTo(0);
    assertThat(out.toString())
        .contains(
            "would uninstall spoken-readables-audio 2.3.0  (SpokenReadablesAudio)",
            "would remove spoken-readables-audio from game test");
    assertThat(config).content().isEqualTo(before);
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();

    assertThat(run("rm", "spoken-readables-audio")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString())
        .contains(
            "- spoken-readables-audio 2.3.0  (SpokenReadablesAudio)",
            "removed spoken-readables-audio from game test");
    assertThat(addons.resolve("SpokenReadablesAudio")).doesNotExist();
    assertThat(addons.resolve("Spoken")).isDirectory();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").entries())
        .containsOnlyKeys("spoken");
    assertThat(config)
        .content()
        .isEqualTo(
            """
            [settings]
            cache_dir = "cache"

            [games.test]
            addons_dir = "Interface/AddOns"

            [games.test.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            tag_prefix = "spoken/"
            asset = "Spoken-*.zip"
            """);

    assertThat(run("remove", "spoken-readables-audio")).isEqualTo(1);
    assertThat(err.toString()).contains("no addon named 'spoken-readables-audio'");
  }

  @Test
  void removeTakesOutAnEntryThatWasNeverInstalled() throws Exception {
    assertThat(run("install", "spoken")).as(err.toString()).isEqualTo(0);
    assertThat(run("remove", "spoken-readables-audio")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("spoken-readables-audio was not installed");
    assertThat(config).content().doesNotContain("readables");
  }

  @Test
  void aRenamedEntryTakesOverItsFoldersAndTheOldNameIsDropped() throws Exception {
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    Files.writeString(
        config,
        Files.readString(config)
            .replace("[games.test.addons.spoken]", "[games.test.addons.player]")
            .replace("depends = [\"spoken\"]", "depends = [\"player\"]"));

    assertThat(run("install")).as(err.toString()).isEqualTo(0);

    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").entries())
        .containsOnlyKeys("player", "spoken-readables-audio");
    assertThat(out.toString())
        .contains("✓ player 3.8.4", "- spoken 3.8.4  (its folders belong to another addon now)");
    assertThat(addons.resolve("Spoken/Spoken.toc")).exists();
  }

  @Test
  void anEntryDeletedByHandIsUninstalledByTheNextFullRun() throws Exception {
    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    var full = Files.readString(config);
    Files.writeString(
        config,
        full.substring(0, full.indexOf("[games.test.addons.spoken-readables-audio]")).strip()
            + "\n");

    // Naming addons only touches those.
    assertThat(run("install", "spoken")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("SpokenReadablesAudio")).isDirectory();

    assertThat(run("update")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString())
        .contains(
            "uninstalling spoken-readables-audio, which is no longer in",
            "- spoken-readables-audio 2.3.0  (SpokenReadablesAudio)");
    assertThat(addons.resolve("SpokenReadablesAudio")).doesNotExist();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "test").entries())
        .containsOnlyKeys("spoken");
  }

  @Test
  void movingToAForkClearsOutFoldersOnlyTheOldReleaseHad() throws Exception {
    assertThat(run("install", "spoken")).as(err.toString()).isEqualTo(0);
    assertThat(addons.resolve("SpokenQuests")).isDirectory();
    github.release("someone/spoken-fork", "spoken/v3.9.0", spoken("3.9.0", false));
    Files.writeString(
        config,
        Files.readString(config)
            .replace("rusty-key/spoken-wow\"\ntag", "someone/spoken-fork\"\ntag"));

    assertThat(run("install", "spoken")).as(err.toString()).isEqualTo(0);

    assertThat(addons.resolve("Spoken/Spoken.toc")).content().contains("3.9.0");
    assertThat(addons.resolve("SpokenQuests")).doesNotExist();
  }

  @Test
  void eachGameHasItsOwnAddonsAndLock() throws Exception {
    var classic = tmp.resolve("classic/AddOns");
    Files.writeString(
        config,
        Files.readString(config)
            + """

            [games.classic]
            addons_dir = "classic/AddOns"

            [games.classic.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            tag_prefix = "spoken/"
            asset = "Spoken-*.zip"
            """);

    assertThat(run("install")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("test:\n", "classic:\n", "✓ spoken 3.8.4");
    assertThat(addons.resolve("Spoken")).isDirectory();
    assertThat(classic.resolve("Spoken")).isDirectory();
    assertThat(LockFile.read(tmp.resolve("wowfam.lock"), "classic").entries())
        .containsOnlyKeys("spoken");

    // Naming an addon runs it in every game that has it, and only those.
    github.release(REPO, "spoken/v3.8.5", spoken("3.8.5", false));
    assertThat(run("update", "spoken-readables-audio")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).doesNotContain("classic:");
    assertThat(classic.resolve("Spoken/Spoken.toc")).content().contains("3.8.4");

    assertThat(run("outdated")).isEqualTo(0);
    assertThat(out.toString()).containsPattern("classic\\s+spoken\\s+3.8.4\\s+3.8.5");
    assertThat(run("update", "-g", "classic")).as(err.toString()).isEqualTo(0);
    assertThat(classic.resolve("Spoken/Spoken.toc")).content().contains("3.8.5");

    // add and remove need to know which game.
    github.release(
        "someone/bars", "v1.0.0", addonZip("Bars-1.0.0.zip", "1.0.0", Map.of("Bars", "")));
    assertThat(run("add", "someone/bars")).isEqualTo(1);
    assertThat(err.toString()).contains("which game? pick one with --game (test, classic)");
    assertThat(run("add", "someone/bars", "-g", "classic")).as(err.toString()).isEqualTo(0);
    assertThat(classic.resolve("Bars")).isDirectory();
    // The new table joins the game's others, not the end of the file.
    assertThat(Files.readString(config).indexOf("[games.classic.addons.bars]"))
        .isGreaterThan(Files.readString(config).indexOf("[games.classic.addons.spoken]"));
    assertThat(run("rm", "bars", "-g", "classic")).as(err.toString()).isEqualTo(0);
    assertThat(classic.resolve("Bars")).doesNotExist();
  }

  /** A home with Battle.net in a Lutris prefix, and its classic era and forever folders. */
  private Map<String, String> lutrisHome() throws Exception {
    var home = tmp.resolve("home");
    WowInstall.create(
        home.resolve("Games/battlenet/drive_c/Program Files (x86)"),
        Map.of("_forever_", "wowf", "_classic_era_", "wow_classic_era"),
        "eu|1|abc|1.60.1.70938|wowf\neu|1|abc|1.15.8.1234|wow_classic_era\n");
    return Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", tmp.resolve("xdg").toString());
  }

  private static final String LUTRIS_WOW =
      "~/Games/battlenet/drive_c/Program Files (x86)/World of Warcraft";

  @Test
  void initFindsTheGamesAndWritesTheGlobalConfig() throws Exception {
    var env = lutrisHome();
    var file = tmp.resolve("xdg/wowfam/wowfam.toml");

    // Nobody to ask: it says how to go on rather than guess.
    assertThat(runAs(env, Optional.empty(), "init")).isEqualTo(1);
    assertThat(err.toString()).contains("wowfam init --yes");
    assertThat(file).doesNotExist();

    assertThat(runAs(env, Optional.of("all\nforever\n"), "init")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString())
        .contains("1  classic_era", "2  forever", "(wowf 1.60.1.70938 (interface 16001))");
    assertThat(file)
        .content()
        .endsWith(
            """
            [settings]
            default_game = "forever"

            [games.classic_era]
            dir = "%s/_classic_era_"

            [games.forever]
            dir = "%s/_forever_"
            """
                .formatted(LUTRIS_WOW, LUTRIS_WOW));

    assertThat(runAs(env, Optional.empty(), "init")).isEqualTo(0);
    assertThat(out.toString()).contains("every game found is already in", "nothing to change");

    // The global file is what every command reads now.
    assertThat(runAs(env, Optional.empty(), "list")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString())
        .contains("classic_era: wow_classic_era 1.15.8.1234", "forever: wowf");
  }

  @Test
  void initPicksSomeGamesOrAFolderItWasPointedAt() throws Exception {
    var env = lutrisHome();
    var file = tmp.resolve("xdg/wowfam/wowfam.toml");

    assertThat(runAs(env, Optional.of("7\n2\n"), "init")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("pick numbers from 1 to 2");
    assertThat(file).content().contains("[games.forever]", "default_game = \"forever\"");
    assertThat(file).content().doesNotContain("classic_era");

    var other =
        WowInstall.create(tmp.resolve("other"), Map.of("_retail_", "wow"), "eu|1|a|12.0.1.1|wow\n");
    assertThat(runAs(env, Optional.empty(), "init", "--dir", other.resolve("_retail_").toString()))
        .as(err.toString())
        .isEqualTo(0);
    assertThat(file)
        .content()
        .contains("[games.retail]\ndir = \"" + other.resolve("_retail_") + "\"")
        .contains("default_game = \"forever\"");
  }

  @Test
  void initAsksForTheFolderWhenItFindsNone() throws Exception {
    var env =
        Map.of(
            "HOME",
            tmp.resolve("empty").toString(),
            "XDG_CONFIG_HOME",
            tmp.resolve("xdg").toString());
    var wow =
        WowInstall.create(
            tmp.resolve("odd place"), Map.of("_retail_", "wow"), "eu|1|a|12.0.1.1|wow\n");

    assertThat(runAs(env, Optional.empty(), "init")).isEqualTo(1);
    assertThat(err.toString()).contains("found no World of Warcraft install", "--dir");

    assertThat(runAs(env, Optional.of("/nowhere\n" + wow + "\n"), "init"))
        .as(err.toString())
        .isEqualTo(0);
    assertThat(out.toString()).contains("no game folder (_retail_, _classic_era_...) in /nowhere");
    assertThat(tmp.resolve("xdg/wowfam/wowfam.toml")).content().contains("[games.retail]");
  }

  @Test
  void initMovesInAnOldConfigFromTheGameFolder() throws Exception {
    var env = lutrisHome();
    var forever =
        tmp.resolve("home/Games/battlenet/drive_c/Program Files (x86)/World of Warcraft/_forever_");
    // How wowfam was set up before global configs: the file and its lock in the game folder.
    config = forever.resolve("wowfam.toml");
    addons = forever.resolve("Interface/AddOns");
    Files.writeString(
        config,
        """
        [settings]
        addons_dir = "Interface/AddOns"

        # the player
        [addons.spoken]
        source = "github:rusty-key/spoken-wow"
        tag_prefix = "spoken/"   # only the player's tags
        asset = "Spoken-*.zip"
        """);
    Files.writeString(
        forever.resolve("wowfam.lock"),
        """
        [addons.spoken]
        source = "github:rusty-key/spoken-wow"
        version = "3.8.4"
        tag = "spoken/v3.8.4"
        asset = "Spoken-3.8.4.zip"
        url = "https://example.invalid/Spoken-3.8.4.zip"
        folders = ["Spoken"]
        """);
    Files.createDirectories(addons.resolve("Spoken"));
    assertThat(run("list")).isEqualTo(1);
    assertThat(err.toString()).contains("wowfam init --import");

    assertThat(runAs(env, Optional.empty(), "init", "--yes")).as(err.toString()).isEqualTo(0);
    assertThat(out.toString()).contains("imported spoken into game forever");
    var file = tmp.resolve("xdg/wowfam/wowfam.toml");
    assertThat(file)
        .content()
        .contains(
            """
            [games.forever]
            dir = "%s/_forever_"

            # the player
            [games.forever.addons.spoken]
            source = "github:rusty-key/spoken-wow"
            tag_prefix = "spoken/"   # only the player's tags
            asset = "Spoken-*.zip"
            """
                .formatted(LUTRIS_WOW));
    assertThat(LockFile.read(tmp.resolve("xdg/wowfam/wowfam.lock"), "forever").get("spoken"))
        .hasValueSatisfying(e -> assertThat(e.version()).isEqualTo("3.8.4"));

    // An explicit --import into a config that already has the game.
    Files.writeString(
        config, Files.readString(config).replace("[addons.spoken]", "[addons.other]"));
    assertThat(runAs(env, Optional.empty(), "init", "--import", config.toString()))
        .as(err.toString())
        .isEqualTo(0);
    assertThat(file).content().contains("[games.forever.addons.other]");
  }
}
