package io.github.tinnet.wowfam.config;

import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Well-known addons, so {@code wowfam add questie} works without the repository. An entry is what
 * {@code add} would otherwise be told: a source and the options it can't, or shouldn't have to,
 * work out. {@code add} writes the resolved source and options to {@code wowfam.toml}, never the
 * registry name, so a later change here never moves an existing setup to another repository.
 *
 * <p>For now the list only holds addons known to work on WoW: Forever (interface 16001): check its
 * release.json or .toc before adding one. Keep entries in alphabetical order. Options are only
 * needed where the repository publishes several things or discovery could pick wrong; the rest
 * {@code add} works out as for any source.
 */
public final class Registry {

  /** The options an entry may preset, in the order they are shown. */
  public static final List<String> OPTIONS = List.of("tag_prefix", "asset", "prerelease");

  /** A well-known addon: its name in the registry and the entry {@code add} starts from. */
  public record Entry(String name, Source source, Map<String, String> options) {
    public Entry {
      if (!OPTIONS.containsAll(options.keySet())) {
        throw new IllegalArgumentException(name + ": options must be among " + OPTIONS);
      }
      options = Map.copyOf(options);
    }
  }

  private static final List<Entry> ENTRIES =
      List.of(
          entry("better-vendor-price", "github:mooreatv/BetterVendorPrice", Map.of()),
          entry("bigwigs", "github:BigWigsMods/BigWigs", Map.of()),
          entry("dbm", "github:DeadlyBossMods/DeadlyBossMods", Map.of()),
          entry("foreverplusplus", "github:xIGBClutchIx/ForeverPlusPlus", Map.of()),
          entry("polestar", "github:fitzforge/polestar", Map.of("asset", "Polestar-*.zip")),
          entry(
              "polestar-recorder",
              "github:fitzforge/polestar",
              Map.of("asset", "Polestar_Recorder-*.zip")),
          entry("questie", "github:Questie/Questie", Map.of("tag_prefix", "bundle/")),
          entry(
              "spoken",
              "github:rusty-key/spoken-wow",
              Map.of("tag_prefix", "spoken/", "asset", "Spoken-*.zip")),
          entry(
              "spoken-bosses-audio",
              "github:rusty-key/spoken-wow",
              Map.of("tag_prefix", "bosses-audio/", "asset", "SpokenBossesAudio-*.zip")),
          entry(
              "spoken-readables-audio",
              "github:rusty-key/spoken-wow",
              Map.of("tag_prefix", "readables-audio/", "asset", "SpokenReadablesAudio-*.zip")),
          entry("whatstraining", "github:fusionpit/WhatsTraining", Map.of()));

  private Registry() {}

  private static Entry entry(String name, String source, Map<String, String> options) {
    return new Entry(name, Source.parse(source), options);
  }

  public static List<Entry> all() {
    return ENTRIES;
  }

  /**
   * Whether {@code input} can only be a registry name: a word that could name an addon, which is
   * neither a URL, an {@code owner/repo} nor a {@code backend:reference}.
   */
  public static boolean isName(String input) {
    return ConfigEditor.NAME.matcher(input.strip()).matches();
  }

  /** The entry called {@code name}, ignoring case. */
  public static Optional<Entry> lookup(String name) {
    var wanted = name.strip();
    return ENTRIES.stream().filter(e -> e.name().equalsIgnoreCase(wanted)).findFirst();
  }

  /** The entry called {@code name}, or an error that suggests the closest names. */
  public static Entry require(String name) {
    return lookup(name)
        .orElseThrow(() -> {
          var suggestions = closest(name);
          return new ConfigException(
              "'"
                  + name.strip()
                  + "' is not a well-known addon."
                  + (suggestions.isEmpty()
                      ? ""
                      : " Did you mean " + String.join(" or ", suggestions) + "?")
                  + " Give owner/repo or a GitHub URL for addons not in the list.");
        });
  }

  /** Names close to {@code name}: those it is a part of, or a typo or two away from. */
  static List<String> closest(String name) {
    var wanted = name.strip().toLowerCase(Locale.ROOT);
    var close =
        ENTRIES.stream()
            .map(Entry::name)
            .filter(
                n -> n.contains(wanted) || distance(n, wanted) <= Math.max(2, wanted.length() / 3))
            .sorted(comparingInt((String n) -> distance(n, wanted)).thenComparing(naturalOrder()))
            .toList();
    return close.subList(0, Math.min(3, close.size()));
  }

  /** Levenshtein distance. */
  static int distance(String a, String b) {
    var previous = new int[b.length() + 1];
    var current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
      }
      var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
