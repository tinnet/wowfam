package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.config.AddonSpec;
import java.nio.file.Path;
import java.util.List;

/**
 * A place addons are published, selected by the part of {@code source} before the colon. Mirrors
 * mise's backends: the first is {@code github} (GitHub releases).
 */
public interface Backend {

    /** The prefix used in {@code source = "<id>:..."}. */
    String id();

    /**
     * Every release of the addon this backend can offer, each with the asset to install, newest
     * first. Choosing among them (requested version, lock file) is not the backend's job.
     */
    List<Release> releases(AddonSpec spec);

    /** Downloads the asset to {@code target}, replacing it if present. */
    void download(Asset asset, Path target);
}
