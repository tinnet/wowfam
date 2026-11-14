package io.github.tinnet.wowfam.config;

import static java.util.Collections.unmodifiableMap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed {@code wowfam.toml}: every game in it, in the order they were written.
 *
 * @param file the file
 * @param defaultGame {@code settings.default_game}: the game {@code add} and {@code remove} use
 *     when none is named
 * @param games game name -> its addons and settings
 */
public record Workspace(Path file, Optional<String> defaultGame, Map<String, Config> games) {

  public Workspace {
    games = unmodifiableMap(new LinkedHashMap<>(games));
  }

  public Path lockFile() {
    return file.resolveSibling(Config.LOCK_FILE_NAME);
  }

  /** The game {@code name}. */
  public Config game(String name) {
    var game = games.get(name);
    if (game == null) {
      throw new ConfigException(
          "no game named '"
              + name
              + "' in "
              + file
              + (games.isEmpty() ? "" : " (it has " + String.join(", ", games.keySet()) + ")"));
    }
    return game;
  }

  /**
   * The one game a command that changes the file works on: {@code wanted} if given, else the
   * default game, else the only game.
   */
  public Config one(Optional<String> wanted) {
    var name = wanted.or(() -> defaultGame);
    if (name.isPresent()) {
      return game(name.orElseThrow());
    }
    if (games.size() == 1) {
      return games.values().iterator().next();
    }
    throw new ConfigException(
        games.isEmpty()
            ? "no games in " + file + "; run wowfam init"
            : "which game? pick one with --game ("
                + String.join(", ", games.keySet())
                + "), or set settings.default_game in "
                + file);
  }

  /** The games a command over addons works on: {@code wanted} if given, else all of them. */
  public List<Config> selected(Optional<String> wanted) {
    if (wanted.isPresent()) {
      return List.of(game(wanted.orElseThrow()));
    }
    if (games.isEmpty()) {
      throw new ConfigException("no games in " + file + "; run wowfam init");
    }
    return List.copyOf(games.values());
  }
}
