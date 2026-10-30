package com.truesim.common.graph;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** Nodes are culled back from what is kept, ordered as added, with lifetimes, dependencies and switches. */
class NodeGraphTest {

    private static int[] dependencies(Schedule s, int position) {
        int[] out = new int[s.dependencyCount(position)];
        for (int i = 0; i < out.length; i++) {
            out[i] = s.dependency(position, i);
        }
        return out;
    }

    @Test
    void nodesNothingNeedsAreCulled() {
        NodeGraph g = new NodeGraph();
        int color = g.resource();
        int unused = g.resource();
        int readback = g.resource();
        int draw = g.node();
        g.write(draw, color);
        int orphan = g.node(); // writes what nothing keeps
        g.write(orphan, unused);
        int post = g.node();
        g.read(post, color).write(post, color);
        int copy = g.node(); // a readback: kept for its side effect, and what it reads with it
        g.read(copy, readback).sideEffects(copy);
        int nothing = g.node(); // no accesses at all
        g.keep(color);
        assertThat(g.nodeCount()).isEqualTo(5);
        assertThat(g.resourceCount()).isEqualTo(3);
        Schedule s = g.compile();
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.node(0)).isEqualTo(draw);
        assertThat(s.node(1)).isEqualTo(post);
        assertThat(s.node(2)).isEqualTo(copy);
        assertThat(s.position(orphan)).isEqualTo(-1);
        assertThat(s.position(nothing)).isEqualTo(-1);
        assertThat(s.position(post)).isEqualTo(1);
        assertThat(s.first(color)).isZero();
        assertThat(s.last(color)).isEqualTo(1);
        assertThat(s.first(unused)).isEqualTo(-1);
        assertThat(s.last(unused)).isEqualTo(-1);
        assertThat(s.first(readback)).isEqualTo(2);
        assertThat(s.resourceCount()).isEqualTo(3);
        assertThat(s).hasToString("Schedule[0, 2, 3]");
        assertThatIllegalArgumentException().isThrownBy(() -> s.position(5));
        assertThatIllegalArgumentException().isThrownBy(() -> s.position(-1));
    }

    @Test
    void dependenciesFollowReadsAndWrites() {
        NodeGraph g = new NodeGraph();
        int a = g.resource();
        int b = g.resource();
        int w1 = g.node();
        g.write(w1, a);           // 0: writes a
        int r1 = g.node();
        g.read(r1, a).write(r1, b); // 1: reads a after 0, writes b
        int r2 = g.node();
        g.read(r2, a).read(r2, a).sideEffects(r2); // 2: reads a after 0 (declared twice: one dependency, one reader)
        int w2 = g.node();
        g.write(w2, a);           // 3: overwrites a after 0, and after its readers 1 and 2
        int r3 = g.node();
        g.read(r3, b).read(r3, a).sideEffects(r3); // 4: reads b after 1, a after 3
        int w3 = g.node();
        g.read(w3, a).write(w3, a).write(w3, b); // 5: reads and writes a after 3 (and reader 4), b after 1 and 4
        g.keep(a).keep(b);
        Schedule s = g.compile();
        assertThat(s.size()).isEqualTo(6);
        assertThat(dependencies(s, 0)).isEmpty();
        assertThat(dependencies(s, 1)).containsExactly(0);
        assertThat(dependencies(s, 2)).containsExactly(0);
        assertThat(dependencies(s, 3)).containsExactly(0, 1, 2);
        assertThat(dependencies(s, 4)).containsExactly(1, 3);
        assertThat(dependencies(s, 5)).containsExactly(1, 3, 4);
    }

    @Test
    void manyReadersAndAccessesGrowTheArrays() {
        NodeGraph g = new NodeGraph();
        int shared = g.resource();
        int[] outputs = new int[20];
        int writer = g.node();
        g.write(writer, shared);
        for (int i = 0; i < 20; i++) {
            outputs[i] = g.resource();
            int reader = g.node();
            g.read(reader, shared).write(reader, outputs[i]);
            for (int extra = 0; extra < 3; extra++) {
                g.read(reader, shared); // many accesses: the access arrays grow
            }
            g.keep(outputs[i]);
        }
        int last = g.node();
        g.write(last, shared); // after 20 readers: depends on all of them, and the writer
        g.keep(shared);
        Schedule s = g.compile();
        assertThat(s.size()).isEqualTo(22);
        assertThat(s.dependencyCount(21)).isEqualTo(21);
        assertThat(s.dependency(21, 0)).isZero();
        assertThat(s.dependency(21, 20)).isEqualTo(20);
    }

    @Test
    void switchesChooseNodesPerRunWithoutAllocating() {
        NodeGraph g = new NodeGraph();
        int color = g.resource();
        int draw = g.node();
        g.write(draw, color);
        int bloom = g.node();
        g.read(bloom, color).write(bloom, color).switchedBy(bloom, 0);
        int grade = g.node();
        g.read(grade, color).write(grade, color).switchedBy(grade, 63);
        g.keep(color);
        Schedule s = g.compile();
        assertThat(s.switchOf(0)).isEqualTo(Schedule.ALWAYS);
        assertThat(s.switchOf(1)).isZero();
        assertThat(s.switchOf(2)).isEqualTo(63);
        int[] out = new int[s.size()];
        assertThat(s.active(-1L, out)).isEqualTo(3);
        assertThat(s.active(0L, out)).isEqualTo(1);
        assertThat(out[0]).isZero();
        assertThat(s.active(1L << 63, out)).isEqualTo(2);
        assertThat(out[1]).isEqualTo(2);
        assertAllocationFree(() -> s.active(1L, out));
        assertThatIllegalArgumentException().isThrownBy(() -> g.switchedBy(draw, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> g.switchedBy(draw, -1));
    }

    @Test
    void misuseIsChecked() {
        NodeGraph g = new NodeGraph();
        int r = g.resource();
        int n = g.node();
        assertThatIllegalArgumentException().isThrownBy(() -> g.read(1, r)).withMessage("No node 1 of 1");
        assertThatIllegalArgumentException().isThrownBy(() -> g.read(-1, r));
        assertThatIllegalArgumentException().isThrownBy(() -> g.write(n, 1)).withMessage("No resource 1 of 1");
        assertThatIllegalArgumentException().isThrownBy(() -> g.write(n, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> g.keep(2));
        assertThatIllegalArgumentException().isThrownBy(() -> g.sideEffects(2));
        // Many resources and nodes: their arrays grow.
        for (int i = 0; i < 40; i++) {
            g.keep(g.resource());
            g.sideEffects(g.node());
        }
        assertThat(g.compile().size()).isEqualTo(40);
    }
}
