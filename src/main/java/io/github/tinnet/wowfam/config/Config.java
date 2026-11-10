package io.github.tinnet.wowfam.config;

import io.github.tinnet.wowfam.util.DependencyOrder;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A parsed {@code wowfam.toml}. Addons keep the order they were written in. */
public record Config(Path file, Settings settings, Map<String, AddonSpec> addons) {

    public static final String FILE_NAME = "wowfam.toml";
    public static final String LOCK_FILE_NAME = "wowfam.lock";

    public Config {
        addons = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(addons));
    }

    public Path lockFile() {
        return file.resolveSibling(LOCK_FILE_NAME);
    }

    /**
     * The named addons (or all, if none are named) and everything they depend on, dependencies
     * first.
     */
    public List<AddonSpec> installOrder(Collection<String> names) {
        var graph = new LinkedHashMap<String, List<String>>();
        addons.forEach((name, spec) -> graph.put(name, spec.depends()));
        Collection<String> roots = names.isEmpty() ? addons.keySet() : names;
        for (var root : roots) {
            if (!addons.containsKey(root)) {
                throw new ConfigException("no addon named '" + root + "' in " + file);
            }
        }
        try {
            return DependencyOrder.resolve(graph, roots).stream().map(addons::get).toList();
        } catch (DependencyOrder.CycleException e) {
            throw new ConfigException(e.getMessage() + " in " + file, e);
        }
    }
}
