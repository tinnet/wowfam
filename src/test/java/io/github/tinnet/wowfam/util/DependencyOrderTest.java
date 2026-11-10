package io.github.tinnet.wowfam.util;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyOrderTest {

    @Test
    void spokenModulesComeAfterSpoken() {
        var graph = Map.of(
                "spoken", List.<String>of(),
                "spoken-quests-audio", List.of("spoken"),
                "spoken-readables-audio", List.of("spoken"));
        assertThat(DependencyOrder.resolve(graph, List.of("spoken-readables-audio")))
                .containsExactly("spoken", "spoken-readables-audio");
    }

    @Test
    void reportsCycles() {
        var graph = Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("a"));
        assertThatThrownBy(() -> DependencyOrder.resolve(graph, List.of("a")))
                .isInstanceOf(DependencyOrder.CycleException.class)
                .hasMessage("dependency cycle: a -> b -> c -> a");
    }

    /** Random DAGs: node i may only depend on nodes with a smaller index. */
    @HegelTest
    void dependenciesAlwaysComeFirstAndNothingIsLost(TestCase tc) {
        int n = tc.draw(integers().min(1).max(12), "nodes");
        var graph = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < n; i++) {
            var deps = new ArrayList<String>();
            if (i > 0) {
                for (int d : tc.draw(lists(integers().min(0).max(i - 1)).maxSize(3), "deps of n" + i)) {
                    deps.add("n" + d);
                }
            }
            graph.put("n" + i, deps);
        }
        var roots = tc.draw(lists(integers().min(0).max(n - 1)).minSize(1).maxSize(n), "roots").stream()
                .map(i -> "n" + i).toList();

        var order = DependencyOrder.resolve(graph, roots);

        assertThat(order).doesNotHaveDuplicates().containsAll(roots);
        var seen = new HashSet<String>();
        for (var node : order) {
            assertThat(seen).as("dependencies of %s", node).containsAll(graph.get(node));
            seen.add(node);
        }
    }
}
