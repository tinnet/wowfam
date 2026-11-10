package io.github.tinnet.wowfam.config;

/**
 * Where an addon comes from, written {@code <backend>:<reference>} as in mise, e.g. {@code
 * github:rusty-key/spoken-wow}.
 */
public record Source(String backend, String reference) {

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

  @Override
  public String toString() {
    return backend + ":" + reference;
  }
}
