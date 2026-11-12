package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.util.Checksum;
import java.net.URI;
import java.util.Optional;

/**
 * A downloadable archive of a release.
 *
 * @param checksum the archive's digest, when the backend publishes one
 */
public record Asset(String name, URI url, long size, Optional<Checksum> checksum) {}
