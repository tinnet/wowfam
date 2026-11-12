package io.github.tinnet.wowfam.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A digest of a file and the algorithm that made it, written {@code <algorithm>:<hex>} as GitHub
 * does, e.g. {@code sha256:9f86d0…}.
 *
 * @param algorithm lower case without punctuation: {@code sha256}, {@code sha1}, {@code md5}
 * @param value lower-case hex
 */
public record Checksum(String algorithm, String value) {

  public static final String SHA256 = "sha256";
  private static final Pattern FORMAT = Pattern.compile("([A-Za-z0-9]+):([0-9A-Fa-f]+)");

  public Checksum {
    algorithm = algorithm.toLowerCase(Locale.ROOT);
    value = value.toLowerCase(Locale.ROOT);
    if (!FORMAT.matcher(algorithm + ":" + value).matches()) {
      throw new IllegalArgumentException("not a checksum: " + algorithm + ":" + value);
    }
  }

  public static Checksum sha256(String hex) {
    return new Checksum(SHA256, hex);
  }

  /** Reads {@code <algorithm>:<hex>}; empty if {@code text} is not of that form. */
  public static Optional<Checksum> parse(String text) {
    var m = FORMAT.matcher(text);
    return m.matches() ? Optional.of(new Checksum(m.group(1), m.group(2))) : Optional.empty();
  }

  @Override
  public String toString() {
    return algorithm + ":" + value;
  }
}
