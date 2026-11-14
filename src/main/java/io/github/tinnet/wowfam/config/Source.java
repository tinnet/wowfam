package io.github.tinnet.wowfam.config;

import java.util.regex.Pattern;

/**
 * Where an addon comes from, written {@code <backend>:<reference>} as in mise, e.g. {@code
 * github:rusty-key/spoken-wow}.
 */
public record Source(String backend, String reference) {

  private static final Pattern GITHUB_URL =
      Pattern.compile(
          "(?i)(?:https?://)?(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:[/?#].*)?");
  private static final Pattern OWNER_REPO = Pattern.compile("[\\w.-]+/[\\w.-]+");

  public Source {
    if (backend == null || backend.isBlank() || reference == null || reference.isBlank()) {
      throw new ConfigException("a source needs a backend and a reference, e.g. github:owner/repo");
    }
  }

  public static Source parse(String text) {
    int colon = text == null ? -1 : text.indexOf(':');
    if (colon <= 0 || colon == text.length() - 1) {
      throw new ConfigException(
          "invalid source '" + text + "': expected <backend>:<reference>, e.g. github:owner/repo");
    }
    return new Source(text.substring(0, colon).strip(), text.substring(colon + 1).strip());
  }

  /**
   * A source as a user types it on the command line: {@code <backend>:<reference>}, a GitHub URL
   * such as {@code https://github.com/owner/repo/releases}, or a bare {@code owner/repo}, which
   * means GitHub.
   */
  public static Source fromInput(String text) {
    var trimmed = text.strip();
    var url = GITHUB_URL.matcher(trimmed);
    if (url.matches()) {
      return new Source("github", url.group(1) + "/" + url.group(2));
    }
    if (OWNER_REPO.matcher(trimmed).matches()) {
      return new Source("github", trimmed);
    }
    return parse(trimmed);
  }

  /**
   * The last part of the reference, e.g. {@code spoken-wow} for {@code
   * github:rusty-key/spoken-wow}.
   */
  public String shortName() {
    return reference.substring(reference.lastIndexOf('/') + 1);
  }

  /**
   * Whether {@code other} is the same source, ignoring case: GitHub owner and repository names are
   * case-insensitive, so {@code Questie/Questie} is {@code questie/questie}.
   */
  public boolean sameAs(Source other) {
    return backend.equalsIgnoreCase(other.backend) && reference.equalsIgnoreCase(other.reference);
  }

  @Override
  public String toString() {
    return backend + ":" + reference;
  }
}
