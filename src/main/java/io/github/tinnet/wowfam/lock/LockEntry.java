package io.github.tinnet.wowfam.lock;

import java.util.List;
import java.util.Optional;

/**
 * What was installed for one addon.
 *
 * @param folders the top-level folders unpacked into the AddOns directory; removed on update
 */
public record LockEntry(
    String source,
    String version,
    String tag,
    String asset,
    String url,
    Optional<String> sha256,
    List<String> folders) {

  public LockEntry {
    folders = List.copyOf(folders);
  }
}
