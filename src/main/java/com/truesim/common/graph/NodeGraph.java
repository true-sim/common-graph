package com.truesim.common.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * Nodes and the resources they read and write, collected in order and {@linkplain #compile() compiled} into a
 * {@link Schedule}: the nodes something needs, in order, with each resource's lifetime and each node's dependencies.
 * A node is needed if it has side effects or writes a resource needed after it; a resource is needed if it is
 * {@linkplain #keep kept} (an output, or contents that outlive the run) or a needed node uses it.
 *
 * <p>What nodes and resources are (passes and images, modules and game state) is the caller's: here they are numbers,
 * in the order added. The frame graph culls and orders its passes this way; the module graph its modules.
 *
 * <pre>{@code
 * NodeGraph g = new NodeGraph();
 * int color = g.resource();
 * int draw = g.node();       g.write(draw, color);
 * int post = g.node();       g.read(post, color); g.write(post, color);
 * g.keep(color);
 * Schedule s = g.compile();  // both nodes, in order; color from position 0 to 1
 * }</pre>
 */
@AllowAllocation("collecting a graph, when it changes")
public final class NodeGraph {

    private int nodes;
    private int resources;
    private boolean[] sideEffects = new boolean[16];
    private int[] switches = new int[16];
    private boolean[] kept = new boolean[16];
    // Accesses in the order declared: node, resource, write.
    private int[] accessNode = new int[64];
    private int[] accessResource = new int[64];
    private boolean[] accessWrites = new boolean[64];
    private int accesses;

    /** Adds a resource; returns its number, the next one from 0. */
    public int resource() {
        if (resources == kept.length) {
            kept = Arrays.copyOf(kept, 2 * resources);
        }
        return resources++;
    }

    /** Adds a node, after the nodes before it; returns its number, the next one from 0. */
    public int node() {
        if (nodes == sideEffects.length) {
            sideEffects = Arrays.copyOf(sideEffects, 2 * nodes);
            switches = Arrays.copyOf(switches, 2 * nodes);
        }
        switches[nodes] = Schedule.ALWAYS;
        return nodes++;
    }

    /** Node {@code node} has effects beyond its resources (a readback, input, saving): it is never culled. */
    public NodeGraph sideEffects(int node) {
        sideEffects[checkNode(node)] = true;
        return this;
    }

    /**
     * Node {@code node} runs only while switch {@code index} (0 to 63) is on, chosen per run without compiling again
     * ({@link Schedule#active}). It must only change resources in place: when it is off, they pass through unchanged.
     */
    public NodeGraph switchedBy(int node, int index) {
        if (index < 0 || index > 63) {
            throw new IllegalArgumentException("Switches are 0 to 63: " + index);
        }
        switches[checkNode(node)] = index;
        return this;
    }

    /** Resource {@code resource} is needed after the run: an output, or contents that outlive it. */
    public NodeGraph keep(int resource) {
        kept[checkResource(resource)] = true;
        return this;
    }

    /** Node {@code node} reads {@code resource}. */
    public NodeGraph read(int node, int resource) {
        return access(node, resource, false);
    }

    /** Node {@code node} writes {@code resource} (or reads and writes it). */
    public NodeGraph write(int node, int resource) {
        return access(node, resource, true);
    }

    private NodeGraph access(int node, int resource, boolean writes) {
        checkNode(node);
        checkResource(resource);
        if (accesses == accessNode.length) {
            accessNode = Arrays.copyOf(accessNode, 2 * accesses);
            accessResource = Arrays.copyOf(accessResource, 2 * accesses);
            accessWrites = Arrays.copyOf(accessWrites, 2 * accesses);
        }
        accessNode[accesses] = node;
        accessResource[accesses] = resource;
        accessWrites[accesses++] = writes;
        return this;
    }

    public int nodeCount() {
        return nodes;
    }

    public int resourceCount() {
        return resources;
    }

    /** Culls, orders and measures the graph as it is now; adding to it afterwards does not change the schedule. */
    public Schedule compile() {
        // Each node's accesses, grouped (in declared order) from the flat list.
        int[] start = new int[nodes + 1];
        for (int a = 0; a < accesses; a++) {
            start[accessNode[a] + 1]++;
        }
        for (int n = 0; n < nodes; n++) {
            start[n + 1] += start[n];
        }
        int[] fill = Arrays.copyOf(start, nodes);
        int[] resourceOf = new int[accesses];
        boolean[] writesOf = new boolean[accesses];
        for (int a = 0; a < accesses; a++) {
            int at = fill[accessNode[a]]++;
            resourceOf[at] = accessResource[a];
            writesOf[at] = accessWrites[a];
        }
        // Culled: walking back from the end, a node is needed if it has side effects or writes a needed resource.
        boolean[] needed = Arrays.copyOf(kept, resources);
        boolean[] keep = new boolean[nodes];
        int count = 0;
        for (int n = nodes - 1; n >= 0; n--) {
            boolean writesNeeded = sideEffects[n];
            for (int a = start[n]; a < start[n + 1]; a++) {
                writesNeeded |= writesOf[a] && needed[resourceOf[a]];
            }
            if (writesNeeded) {
                keep[n] = true;
                count++;
                for (int a = start[n]; a < start[n + 1]; a++) {
                    needed[resourceOf[a]] = true;
                }
            }
        }
        // In order: the nodes as added, which is an order every dependency respects.
        int[] order = new int[count];
        int[] position = new int[nodes];
        Arrays.fill(position, -1);
        for (int n = 0, p = 0; n < nodes; n++) {
            if (keep[n]) {
                position[n] = p;
                order[p++] = n;
            }
        }
        // Lifetimes, and dependencies: on the last writer of what a node reads or writes (read after write, write
        // after write), and on the readers since that writer of what it writes (write after read).
        int[] first = new int[resources];
        int[] last = new int[resources];
        Arrays.fill(first, -1);
        Arrays.fill(last, -1);
        int[] lastWriter = new int[resources];
        Arrays.fill(lastWriter, -1);
        int[][] readers = new int[resources][];
        int[] readerCount = new int[resources];
        int[][] dependencies = new int[count][];
        int[] scratch = new int[8];
        for (int p = 0; p < count; p++) {
            int n = order[p];
            int deps = 0;
            for (int a = start[n]; a < start[n + 1]; a++) {
                int r = resourceOf[a];
                if (first[r] < 0) {
                    first[r] = p;
                }
                last[r] = p;
                if (lastWriter[r] >= 0) {
                    scratch = add(scratch, deps, lastWriter[r]);
                    deps = distinct(scratch, deps);
                }
                if (writesOf[a]) {
                    for (int i = 0; i < readerCount[r]; i++) { // earlier nodes only: this one's reads come below
                        scratch = add(scratch, deps, readers[r][i]);
                        deps = distinct(scratch, deps);
                    }
                }
            }
            for (int a = start[n]; a < start[n + 1]; a++) { // then this node's own effects on the resources
                int r = resourceOf[a];
                if (writesOf[a]) {
                    lastWriter[r] = p;
                    readerCount[r] = 0;
                } else {
                    if (readers[r] == null) {
                        readers[r] = new int[4];
                    } else if (readerCount[r] == readers[r].length) {
                        readers[r] = Arrays.copyOf(readers[r], 2 * readerCount[r]);
                    }
                    if (readerCount[r] == 0 || readers[r][readerCount[r] - 1] != p) {
                        readers[r][readerCount[r]++] = p;
                    }
                }
            }
            int[] sorted = Arrays.copyOf(scratch, deps);
            Arrays.sort(sorted);
            dependencies[p] = sorted;
        }
        int[] switchOf = new int[count];
        for (int p = 0; p < count; p++) {
            switchOf[p] = switches[order[p]];
        }
        return new Schedule(nodes, order, position, first, last, dependencies, switchOf);
    }

    private static int[] add(int[] array, int size, int value) {
        int[] grown = size == array.length ? Arrays.copyOf(array, 2 * size) : array;
        grown[size] = value;
        return grown;
    }

    /** Keeps the value just added at {@code size} only if it is new: returns the new size. */
    private static int distinct(int[] array, int size) {
        for (int i = 0; i < size; i++) {
            if (array[i] == array[size]) {
                return size;
            }
        }
        return size + 1;
    }

    private int checkNode(int node) {
        if (node < 0 || node >= nodes) {
            throw new IllegalArgumentException("No node " + node + " of " + nodes);
        }
        return node;
    }

    private int checkResource(int resource) {
        if (resource < 0 || resource >= resources) {
            throw new IllegalArgumentException("No resource " + resource + " of " + resources);
        }
        return resource;
    }
}
