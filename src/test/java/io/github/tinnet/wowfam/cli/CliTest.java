package io.github.tinnet.wowfam.cli;

import static io.github.tinnet.wowfam.FakeGitHub.addonZip;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinnet.wowfam.FakeGitHub;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  private int downloads() {
    return (int) github.requests().stream().filter(r -> r.contains("/download/")).count();
  }
}
