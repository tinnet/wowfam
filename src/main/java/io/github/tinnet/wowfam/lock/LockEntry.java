package io.github.tinnet.wowfam.lock;

import io.github.tinnet.wowfam.util.Checksum;
import java.util.List;
import java.util.Optional;

/**
 * What was installed for one addon.
 *
 * @param checksum the archive's digest and its algorithm, when the backend published one
 * @param folders the top-level folders unpacked into the AddOns directory; removed on update
 */
public record LockEntry(
    String source,
    String version,
    String tag,
    String asset,
    String url,
    Optional<Checksum> checksum,
    List<String> folders) {

  public LockEntry {
    folders = List.copyOf(folders);
  }
}
