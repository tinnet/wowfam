package io.github.tinnet.wowfam.config;

import java.util.List;
import java.util.stream.Stream;

/**
 * Shell commands run around an addon's installation, from the {@code [hooks]} table or an addon's
 * own {@code hooks}.
 *
 * @param preInstall run after the release is unpacked into a staging folder and before its folders
 *     are moved into the AddOns directory; a non-zero exit stops the addon from being installed
 * @param postInstall run once the folders are in the AddOns directory and recorded in the lock
 */
public record Hooks(List<String> preInstall, List<String> postInstall) {

  public static final Hooks NONE = new Hooks(List.of(), List.of());

  public Hooks {
    preInstall = List.copyOf(preInstall);
    postInstall = List.copyOf(postInstall);
  }

  /** These hooks followed by {@code more}'s. */
  public Hooks then(Hooks more) {
    return new Hooks(
        Stream.concat(preInstall.stream(), more.preInstall.stream()).toList(),
        Stream.concat(postInstall.stream(), more.postInstall.stream()).toList());
  }
}
