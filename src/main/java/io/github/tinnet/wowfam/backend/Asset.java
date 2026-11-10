package io.github.tinnet.wowfam.backend;

import java.net.URI;
import java.util.Optional;

/**
 * A downloadable archive of a release.
 *
 * @param sha256 lowercase hex digest, when the backend publishes one
 */
public record Asset(String name, URI url, long size, Optional<String> sha256) {}
