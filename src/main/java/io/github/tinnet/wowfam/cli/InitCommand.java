package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.config.Config;
import io.github.tinnet.wowfam.config.ConfigEditor;
import io.github.tinnet.wowfam.config.ConfigException;
import io.github.tinnet.wowfam.config.ConfigLoader;
import io.github.tinnet.wowfam.config.Workspace;
import io.github.tinnet.wowfam.config.WowInstalls;
import io.github.tinnet.wowfam.config.WowInstalls.Found;
import io.github.tinnet.wowfam.lock.LockFile;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = {
      "Find your World of Warcraft games and write them to wowfam.toml.",
      "Looks where the Battle.net launcher installs on Windows and macOS, and in the Wine prefixes "
          + "of Lutris, Steam, Bottles, Heroic and Faugus on Linux, and asks which games to add. "
          + "Run it again to add a game; games already in the file are kept. A wowfam.toml from "
          + "before global configs, found in a game folder or given with --import, is moved in."
    })
final class InitCommand implements Callable<Integer> {

  @ParentCommand WowfamCommand root;

  @Spec CommandSpec spec;

  @Option(
      names = "--dir",
      paramLabel = "DIR",
      description =
          "Your World of Warcraft folder, or one game folder such as _retail_, instead of looking"
              + " for it. Can be repeated.")
  List<Path> dirs = new ArrayList<>();

  @Option(
      names = {"-y", "--yes"},
      description = "Add every game found and import old configs without asking.")
  boolean yes;

  @Option(
      names = "--import",
      paramLabel = "FILE",
      description = "A wowfam.toml from before global configs, to move into this one.")
  @Nullable Path importFile;

  private PrintWriter out = new PrintWriter(java.io.Writer.nullWriter());

  @Override
  public Integer call() {
    out = spec.commandLine().getOut();
    var file = root.configFile();
    var loader = root.loader();
    var exists = Files.exists(file);
    var text = exists ? read(file) : "";
    var workspace =
        exists ? loader.parse(file, text) : new Workspace(file, Optional.empty(), Map.of());
    var configured = new HashSet<Path>();
    workspace.games().values().forEach(g -> configured.add(folderOf(g)));

    List<Found> candidates;
    if (!dirs.isEmpty()) {
      candidates = new ArrayList<>();
      for (var dir : dirs) {
        var games = WowInstalls.gamesIn(dir);
        if (games.isEmpty()) {
          throw new ConfigException(
              "no World of Warcraft game folder (_retail_, _classic_era_...) in " + dir);
        }
        candidates.addAll(games);
      }
    } else if (importFile != null) {
      candidates = List.of();
    } else {
      candidates =
          WowInstalls.find(System.getProperty("os.name", ""), root.env, root.home()).stream()
              .toList();
    }
    var fresh = candidates.stream().filter(f -> !configured.contains(real(f.dir()))).toList();
    if (!candidates.isEmpty() && fresh.isEmpty()) {
      out.println("every game found is already in " + file);
    }

    var chosen = choose(fresh, candidates.isEmpty() && importFile == null);
    var taken = new HashSet<>(workspace.games().keySet());
    var added = new LinkedHashMap<String, Found>();
    for (var game : chosen) {
      var name = game.name();
      for (int n = 2; taken.contains(name); n++) {
        name = game.name() + "-" + n;
      }
      taken.add(name);
      added.put(name, game);
      text = ConfigEditor.append(text, ConfigEditor.gameTable(name, display(game.dir())));
    }

    // Old configs: one in a game folder being added, or the one given.
    var imports = new LinkedHashMap<Path, String>(); // old file -> game
    added.forEach((name, game) -> {
      var old = game.dir().resolve(Config.FILE_NAME);
      if (isOldLayout(old)) {
        imports.put(old, name);
      }
    });
    if (importFile != null) {
      var old = importFile.toAbsolutePath().normalize();
      if (!isOldLayout(old)) {
        throw new ConfigException(old + " has no top-level [addons] to import");
      }
      var target = importTarget(loader, old, workspace, added);
      if (target.isEmpty()) {
        var addonsDir = loader.oldLayoutAddonsDir(old, read(old));
        var games = WowInstalls.gamesIn(addonsDir);
        if (games.size() != 1) {
          throw new ConfigException(
              "no game in "
                  + file
                  + " installs into "
                  + addonsDir
                  + ", where "
                  + old
                  + " did; add that game first (wowfam init --dir <its folder>)");
        }
        var game = games.getFirst();
        var name = game.name();
        for (int n = 2; taken.contains(name); n++) {
          name = game.name() + "-" + n;
        }
        taken.add(name);
        added.put(name, game);
        text = ConfigEditor.append(text, ConfigEditor.gameTable(name, display(game.dir())));
        target = Optional.of(name);
      }
      imports.put(old, target.orElseThrow());
    }

    var explicit = importFile == null ? null : importFile.toAbsolutePath().normalize();
    var lockMoves = new ArrayList<Runnable>();
    for (var entry : imports.entrySet()) {
      var old = entry.getKey();
      var game = entry.getValue();
      var oldText = read(old);
      var sources = ConfigLoader.oldLayoutSources(old, oldText);
      if (!old.equals(explicit)
          && !confirm(
              "import the "
                  + sources.size()
                  + " addons of "
                  + old
                  + " into game "
                  + game
                  + "? [Y/n] ")) {
        continue;
      }
      text = ConfigEditor.appendToGame(text, game, ConfigEditor.importOldLayout(oldText, game));
      var imported = loader.parse(file, text).game(game).addons();
      for (var s : sources.entrySet()) {
        var spec = imported.get(s.getKey());
        if (spec == null || !spec.source().equals(s.getValue())) {
          throw new ConfigException(
              "cannot import " + old + " as text; copy its addons over by hand");
        }
      }
      if (oldText.contains("[hooks]") || oldText.contains("cache_dir")) {
        out.println(
            "note: the [settings] and [hooks] of "
                + old
                + " were not imported; copy what you need");
      }
      var oldLock = old.resolveSibling(Config.LOCK_FILE_NAME);
      var oldAddonsDir = loader.oldLayoutAddonsDir(old, oldText);
      lockMoves.add(() -> {
        if (!Files.exists(oldLock)) {
          return;
        }
        var target = loader.load(file).game(game);
        if (!real(oldAddonsDir).equals(real(target.settings().addonsDir()))) {
          out.println(
              "note: "
                  + oldLock
                  + " was for "
                  + oldAddonsDir
                  + ", not "
                  + target.settings().addonsDir()
                  + "; wowfam install will reinstall the addons");
          return;
        }
        var lock = LockFile.read(target.lockFile(), game);
        LockFile.readOldLayout(oldLock)
            .forEach((name, e) -> {
              if (imported.containsKey(name) && lock.get(name).isEmpty()) {
                lock.put(name, e);
              }
            });
        lock.write();
      });
      out.println("imported " + String.join(", ", sources.keySet()) + " into game " + game);
    }

    var all = new ArrayList<>(workspace.games().keySet());
    all.addAll(added.keySet());
    if (workspace.defaultGame().isEmpty() && !all.isEmpty()) {
      // A game that came with addons, else one whose client is known, else the first.
      var preferred =
          imports.values().stream()
              .findFirst()
              .or(() ->
                  added.entrySet().stream()
                      .filter(e -> e.getValue().game().isPresent())
                      .findFirst()
                      .map(Map.Entry::getKey))
              .orElseGet(() -> added.isEmpty() ? all.getFirst() : added.keySet().iterator().next());
      var pick =
          all.size() == 1 ? preferred : ask("default game for add and remove", preferred, all);
      text = ConfigEditor.setDefaultGame(text, pick);
    }

    if (added.isEmpty() && imports.isEmpty() && exists) {
      out.println("nothing to change in " + file);
      return 0;
    }
    if (!exists) {
      text =
          "# wowfam: your World of Warcraft addons. `wowfam add <GitHub repository>` adds one.\n"
              + "# https://github.com/tinnet/wowfam#wowfamtoml\n\n"
              + text;
    }
    loader.parse(file, text); // never write a file wowfam can't read
    try {
      var parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      throw new ConfigException("cannot create " + file.getParent() + ": " + e.getMessage(), e);
    }
    ConfigEditor.write(file, text);
    lockMoves.forEach(Runnable::run);
    added.forEach((name, game) ->
        out.printf(
            "added game %s: %s%s%n",
            name, display(game.dir()), game.game().map(g -> " (" + g + ")").orElse("")));
    out.println("wrote " + file);
    out.println(
        imports.isEmpty()
            ? "next: wowfam add <GitHub repository>, e.g. wowfam add Questie/Questie"
            : "next: wowfam install");
    return 0;
  }

  /** The games to add, asking when someone can answer. */
  private List<Found> choose(List<Found> fresh, boolean nothingFound) {
    if (nothingFound) {
      if (root.answers.isEmpty()) {
        throw new ConfigException(
            "found no World of Warcraft install; point to it with wowfam init --dir <folder>");
      }
      out.println("found no World of Warcraft install.");
      while (true) {
        var answer =
            prompt("path to your World of Warcraft folder (or a game folder such as _retail_): ");
        if (answer.isBlank()) {
          throw new ConfigException("no game added");
        }
        var games = WowInstalls.gamesIn(Path.of(expandHome(answer)));
        if (!games.isEmpty()) {
          return games;
        }
        out.println("no game folder (_retail_, _classic_era_...) in " + answer);
      }
    }
    if (fresh.isEmpty()) {
      return List.of();
    }
    out.println("found:");
    for (int i = 0; i < fresh.size(); i++) {
      var f = fresh.get(i);
      out.printf(
          "  %d  %-12s %s%s%n",
          i + 1, f.name(), display(f.dir()), f.game().map(g -> "  (" + g + ")").orElse(""));
    }
    if (yes || !dirs.isEmpty()) {
      return fresh;
    }
    if (root.answers.isEmpty()) {
      throw new ConfigException("add them with wowfam init --yes, or pick folders with --dir");
    }
    while (true) {
      var answer =
          prompt("add which? [all], numbers such as 1 3, none, or the path of another folder: ")
              .strip();
      if (answer.isEmpty() || answer.equalsIgnoreCase("all") || answer.equalsIgnoreCase("y")) {
        return fresh;
      }
      if (answer.equalsIgnoreCase("none") || answer.equalsIgnoreCase("n")) {
        return List.of();
      }
      if (answer.matches("[0-9 ,]+")) {
        var picked = new ArrayList<Found>();
        boolean valid = true;
        for (var n : answer.split("[ ,]+")) {
          if (n.isEmpty()) {
            continue;
          }
          int i = Integer.parseInt(n) - 1;
          if (i < 0 || i >= fresh.size()) {
            valid = false;
            break;
          }
          picked.add(fresh.get(i));
        }
        if (valid && !picked.isEmpty()) {
          return picked;
        }
        out.println("pick numbers from 1 to " + fresh.size());
        continue;
      }
      var games = WowInstalls.gamesIn(Path.of(expandHome(answer)));
      if (!games.isEmpty()) {
        return games;
      }
      out.println("no game folder (_retail_, _classic_era_...) in " + answer);
    }
  }

  /** The game of {@code workspace} or {@code added} that installs where {@code old} did. */
  private static Optional<String> importTarget(
      ConfigLoader loader, Path old, Workspace workspace, Map<String, Found> added) {
    var oldDir = real(loader.oldLayoutAddonsDir(old, read(old)));
    for (var g : workspace.games().values()) {
      if (real(g.settings().addonsDir()).equals(oldDir)) {
        return Optional.of(g.name());
      }
    }
    for (var e : added.entrySet()) {
      if (real(e.getValue().dir().resolve("Interface").resolve("AddOns")).equals(oldDir)) {
        return Optional.of(e.getKey());
      }
    }
    return Optional.empty();
  }

  private boolean confirm(String question) {
    if (yes || root.answers.isEmpty()) {
      return true;
    }
    var answer = prompt(question).strip();
    return answer.isEmpty() || answer.toLowerCase(java.util.Locale.ROOT).startsWith("y");
  }

  private String ask(String question, String preferred, List<String> options) {
    if (yes || root.answers.isEmpty()) {
      return preferred;
    }
    while (true) {
      var answer =
          prompt(question + " (" + String.join(", ", options) + ") [" + preferred + "]: ").strip();
      if (answer.isEmpty()) {
        return preferred;
      }
      if (options.contains(answer)) {
        return answer;
      }
    }
  }

  private String prompt(String question) {
    out.print(question);
    out.flush();
    try {
      var line = root.answers.orElseThrow().readLine();
      return line == null ? "" : line;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String expandHome(String path) {
    return path.equals("~") || path.startsWith("~/")
        ? root.home().resolve(path.length() <= 2 ? "" : path.substring(2)).toString()
        : path;
  }

  /** {@code path}, with the home folder written {@code ~}, so the file travels with dotfiles. */
  private String display(Path path) {
    var home = root.home().toAbsolutePath().normalize();
    var p = path.toAbsolutePath().normalize();
    return p.startsWith(home) && !p.equals(home)
        ? "~/" + home.relativize(p).toString().replace('\\', '/')
        : p.toString();
  }

  /** The game folder of a configured game, or its AddOns folder when it names none. */
  private static Path folderOf(Config game) {
    return real(
        game.settings()
            .gameDir()
            .orElseGet(() -> {
              var addons = game.settings().addonsDir();
              var interfaceDir = addons.getParent();
              return interfaceDir != null && interfaceDir.getParent() != null
                  ? interfaceDir.getParent()
                  : addons;
            }));
  }

  private static boolean isOldLayout(Path file) {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try {
      return Files.readString(file).lines().anyMatch(l -> l.strip().startsWith("[addons."));
    } catch (IOException e) {
      return false;
    }
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }
}
