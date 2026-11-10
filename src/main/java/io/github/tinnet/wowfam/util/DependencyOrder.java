package io.github.tinnet.wowfam.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orders nodes so every node comes after the nodes it depends on (depth-first topological sort).
 */
public final class DependencyOrder {

  private DependencyOrder() {}

  public static final class CycleException extends RuntimeException {
    private final List<String> cycle;

    CycleException(List<String> cycle) {
      super("dependency cycle: " + String.join(" -> ", cycle));
      this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
      return cycle;
    }
  }

  /**
   * @param graph node -> the nodes it depends on; dependencies that are not keys are an error
   * @param roots the nodes wanted; their dependencies are pulled in transitively
   * @return the roots and their transitive dependencies, dependencies first, otherwise in the order
   *     the roots were given
   */
  public static List<String> resolve(
      Map<String, ? extends Collection<String>> graph, Collection<String> roots) {
    var done = new LinkedHashSet<String>();
    var path = new LinkedHashMap<String, Boolean>();
    for (var root : roots) {
      visit(root, graph, done, path);
    }
    return new ArrayList<>(done);
  }

  private static void visit(
      String node,
      Map<String, ? extends Collection<String>> graph,
      Set<String> done,
      Map<String, Boolean> path) {
    if (done.contains(node)) {
      return;
    }
    if (path.containsKey(node)) {
      var cycle = new ArrayList<String>();
      boolean in = false;
      for (var p : path.keySet()) {
        in |= p.equals(node);
        if (in) {
          cycle.add(p);
        }
      }
      cycle.add(node);
      throw new CycleException(cycle);
    }
    var deps = graph.get(node);
    if (deps == null) {
      throw new IllegalArgumentException("unknown addon: " + node);
    }
    path.put(node, Boolean.TRUE);
    for (var dep : deps) {
      visit(dep, graph, done, path);
    }
    path.remove(node);
    done.add(node);
  }
}
