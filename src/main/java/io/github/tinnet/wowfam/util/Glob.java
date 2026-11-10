package io.github.tinnet.wowfam.util;

import java.util.regex.Pattern;

/** Minimal shell-style glob for release asset names: {@code *} and {@code ?}, case-insensitive. */
public final class Glob {

  private final String glob;
  private final Pattern pattern;

  private Glob(String glob) {
    this.glob = glob;
    var regex = new StringBuilder();
    var literal = new StringBuilder();
    for (char c : glob.toCharArray()) {
      if (c == '*' || c == '?') {
        if (!literal.isEmpty()) {
          regex.append(Pattern.quote(literal.toString()));
          literal.setLength(0);
        }
        regex.append(c == '*' ? ".*" : ".");
      } else {
        literal.append(c);
      }
    }
    if (!literal.isEmpty()) {
      regex.append(Pattern.quote(literal.toString()));
    }
    this.pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  public static Glob of(String glob) {
    return new Glob(glob);
  }

  public boolean matches(String name) {
    return pattern.matcher(name).matches();
  }

  @Override
  public String toString() {
    return glob;
  }
}
