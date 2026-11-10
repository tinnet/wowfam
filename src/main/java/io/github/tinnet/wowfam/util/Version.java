package io.github.tinnet.wowfam.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A lenient, SemVer-like version: one or more dot-separated numbers, optionally followed by
 * {@code -prerelease} and/or {@code +build}. A leading {@code v} is accepted and dropped.
 *
 * <p>Ordering follows SemVer precedence: numeric parts compare numerically (missing parts count as
 * zero), a release sorts above any of its prereleases, and build metadata is ignored for precedence
 * but kept for {@link #toString()}.
 */
public record Version(List<BigInteger> numbers, List<String> prerelease, String build, String original)
        implements Comparable<Version> {

    private static final Pattern SHAPE = Pattern.compile(
            "[vV]?(?<nums>\\d+(?:\\.\\d+)*)(?:-(?<pre>[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+(?<build>[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    public Version {
        numbers = List.copyOf(numbers);
        prerelease = List.copyOf(prerelease);
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("a version needs at least one number");
        }
    }

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        var trimmed = text.strip();
        var m = SHAPE.matcher(trimmed);
        if (!m.matches()) {
            return Optional.empty();
        }
        var nums = new ArrayList<BigInteger>();
        for (var part : m.group("nums").split("\\.")) {
            nums.add(new BigInteger(part));
        }
        var pre = m.group("pre") == null ? List.<String>of() : List.of(m.group("pre").split("\\."));
        return Optional.of(new Version(nums, pre, m.group("build"), trimmed));
    }

    public static Version of(String text) {
        return parse(text).orElseThrow(() -> new IllegalArgumentException("not a version: " + text));
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    /**
     * Whether this version satisfies a requested version in the style of mise: {@code latest}
     * matches everything, otherwise the request is a prefix of numeric parts ({@code 3} matches
     * {@code 3.8.5}, {@code 3.8} matches {@code 3.8.5} but not {@code 3.10.0}), or an exact version
     * including its prerelease.
     */
    public boolean satisfies(String requested) {
        if (requested == null || requested.isBlank() || requested.equalsIgnoreCase("latest")) {
            return true;
        }
        var wanted = parse(requested);
        if (wanted.isEmpty()) {
            return false;
        }
        var w = wanted.get();
        if (w.numbers.size() > numbers.size()) {
            return false;
        }
        for (int i = 0; i < w.numbers.size(); i++) {
            if (!w.numbers.get(i).equals(numbers.get(i))) {
                return false;
            }
        }
        return !w.isPrerelease() || w.prerelease.equals(prerelease);
    }

    @Override
    public int compareTo(Version other) {
        int len = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < len; i++) {
            var a = i < numbers.size() ? numbers.get(i) : BigInteger.ZERO;
            var b = i < other.numbers.size() ? other.numbers.get(i) : BigInteger.ZERO;
            int c = a.compareTo(b);
            if (c != 0) {
                return c;
            }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
        }
        int n = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean an = NUMERIC.matcher(a).matches();
        boolean bn = NUMERIC.matcher(b).matches();
        if (an && bn) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (an != bn) {
            return an ? -1 : 1;
        }
        return a.compareTo(b);
    }

    /** The canonical form, without a leading {@code v}. */
    @Override
    public String toString() {
        var sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(numbers.get(i));
        }
        if (!prerelease.isEmpty()) {
            sb.append('-').append(String.join(".", prerelease));
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }

    /** Equality is precedence-based, so it agrees with {@link #compareTo}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        int last = numbers.size();
        while (last > 1 && numbers.get(last - 1).signum() == 0) {
            last--;
        }
        return 31 * numbers.subList(0, last).hashCode() + prerelease.hashCode();
    }
}
