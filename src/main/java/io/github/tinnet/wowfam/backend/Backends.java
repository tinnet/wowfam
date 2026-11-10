package io.github.tinnet.wowfam.backend;

import io.github.tinnet.wowfam.config.ConfigException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The backends known to this build, by id. */
public final class Backends {

  private final Map<String, Backend> byId = new LinkedHashMap<>();

  public Backends(List<? extends Backend> backends) {
    for (var b : backends) {
      byId.put(b.id(), b);
    }
  }

  public Backend get(String id) {
    var backend = byId.get(id);
    if (backend == null) {
      throw new ConfigException(
          "unknown backend '" + id + "' (known: " + String.join(", ", byId.keySet()) + ")");
    }
    return backend;
  }
}
