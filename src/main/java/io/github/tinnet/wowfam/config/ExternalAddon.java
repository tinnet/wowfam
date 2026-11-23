package io.github.tinnet.wowfam.config;

import java.util.List;
import java.util.Optional;

/**
 * An {@code [addons.<name>]} table with {@code external} instead of a source: folders in AddOns
 * that another manager (or the user) keeps. wowfam never installs, updates or deletes them; it only
 * knows they are wanted, so it doesn't report them as unknown, refuses to install over them, and
 * lets other entries depend on them.
 *
 * @param name the table name
 * @param manager who keeps the folders, e.g. {@code WowUp}; empty for {@code external = true}
 * @param folders the addon folders in AddOns
 */
public record ExternalAddon(String name, Optional<String> manager, List<String> folders) {

  public ExternalAddon {
    folders = List.copyOf(folders);
  }

  /** {@code external}, or {@code external (WowUp)}. */
  public String describe() {
    return manager.map(m -> "external (" + m + ")").orElse("external");
  }
}
