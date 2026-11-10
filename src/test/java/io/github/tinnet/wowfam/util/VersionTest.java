package io.github.tinnet.wowfam.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.Generator;
import dev.hegel.Generators;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class VersionTest {

  /** Versions as text, in the shapes addon tags use: 1-4 numbers, sometimes a prerelease. */
  static final Generator<String> VERSION_TEXT =
      Generators.composite(tc -> {
        var nums = tc.draw(lists(integers().min(0).max(30)).minSize(1).maxSize(4));
        var sb = new StringBuilder(String.join(".", nums.stream().map(String::valueOf).toList()));
        if (tc.draw(Generators.booleans())) {
          sb.append('-')
              .append(tc.draw(sampledFrom("alpha", "beta", "rc")))
              .append('.')
              .append(tc.draw(integers().min(0).max(9)));
        }
        return sb.toString();
      });

  @CsvSource({
    "1.0.0-alpha, 1.0.0-alpha.1",
    "1.0.0-alpha.1, 1.0.0-alpha.beta",
    "1.0.0-beta.2, 1.0.0-beta.11",
    "1.0.0-rc.1, 1.0.0",
    "3.8.4, 3.8.5",
    "3.8.4-beta.1, 3.8.4",
    "3.9, 3.10"
  })
  @ParameterizedTest
  void ordersLikeSemver(String lower, String higher) {
    assertThat(Version.of(lower)).isLessThan(Version.of(higher));
  }

  @Test
  void acceptsLeadingVAndTrailingZeros() {
    assertThat(Version.of("v3.8")).isEqualByComparingTo(Version.of("3.8.0"));
    assertThat(Version.of("v3.8"))
        .isEqualTo(Version.of("3.8.0"))
        .hasSameHashCodeAs(Version.of("3.8.0"));
    assertThat(Version.of("v3.8.5")).hasToString("3.8.5");
  }

  @Test
  void rejectsNonVersions() {
    assertThat(Version.parse("latest")).isEmpty();
    assertThat(Version.parse("spoken/v3.8.5")).isEmpty();
    assertThat(Version.parse("")).isEmpty();
  }

  @Test
  void satisfiesPrefixesLikeMise() {
    var v = Version.of("3.8.5");
    assertThat(v.satisfies("latest")).isTrue();
    assertThat(v.satisfies("3")).isTrue();
    assertThat(v.satisfies("3.8")).isTrue();
    assertThat(v.satisfies("3.8.5")).isTrue();
    assertThat(v.satisfies("3.1")).isFalse();
    assertThat(Version.of("3.10.0").satisfies("3.1")).isFalse();
    assertThat(v.satisfies("3.8.5.1")).isFalse();
  }

  @HegelTest
  void toStringRoundTrips(TestCase tc) {
    var text = tc.draw(VERSION_TEXT, "text");
    var v = Version.of(text);
    assertThat(Version.of(v.toString())).isEqualTo(v);
    assertThat(v).hasToString(text);
  }

  @HegelTest
  void compareIsAntisymmetricAndConsistentWithEquals(TestCase tc) {
    var a = Version.of(tc.draw(VERSION_TEXT, "a"));
    var b = Version.of(tc.draw(VERSION_TEXT, "b"));
    assertThat(Integer.signum(a.compareTo(b))).isEqualTo(-Integer.signum(b.compareTo(a)));
    assertThat(a.compareTo(b) == 0).isEqualTo(a.equals(b));
    if (a.equals(b)) {
      assertThat(a).hasSameHashCodeAs(b);
    }
  }

  @HegelTest
  void sortingIsTotal(TestCase tc) {
    var versions =
        new ArrayList<Version>(
            tc.draw(lists(VERSION_TEXT.map(Version::of)).maxSize(12), "versions"));
    Collections.sort(versions);
    for (int i = 0; i < versions.size(); i++) {
      for (int j = i + 1; j < versions.size(); j++) {
        assertThat(versions.get(i)).isLessThanOrEqualTo(versions.get(j));
      }
    }
  }

  @HegelTest
  void everyNumericPrefixIsSatisfied(TestCase tc) {
    var v = Version.of(tc.draw(VERSION_TEXT, "version"));
    int n = tc.draw(integers().min(1).max(v.numbers().size()), "prefix length");
    List<String> prefix = v.numbers().subList(0, n).stream().map(Object::toString).toList();
    assertThat(v.satisfies(String.join(".", prefix))).isTrue();
  }

  @HegelTest
  void aReleaseOutranksItsPrereleases(TestCase tc) {
    var release = Version.of(tc.draw(VERSION_TEXT, "version").replaceFirst("-.*", ""));
    var pre = Version.of(release + "-" + tc.draw(sampledFrom("alpha", "beta.2", "rc.1"), "pre"));
    assertThat(pre).isLessThan(release);
  }
}
