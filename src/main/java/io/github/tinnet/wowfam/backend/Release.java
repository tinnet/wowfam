package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.util.Version;

/**
 * One installable release of an addon.
 *
 * @param version parsed from the tag
 * @param tag the backend's identifier for the release (a git tag for GitHub)
 * @param asset the archive picked for this addon
 */
public record Release(Version version, String tag, Asset asset) {}
