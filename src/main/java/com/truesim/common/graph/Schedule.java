package com.truesim.common.graph;

import java.util.Arrays;

/**
 * A compiled {@link NodeGraph}, immutable: the needed nodes in order (by position, from 0), each resource's lifetime
 * in positions, each position's dependencies, and the switch each node runs under. Reading it allocates nothing.
 */
public final class Schedule {

    /** The switch of a node that always runs. */
    public static final int ALWAYS = -1;

    private final int nodes;
    private final int[] order;
    private final int[] position;
    private final int[] first;
    private final int[] last;
    private final int[][] dependencies;
    private final int[] switchOf;

    Schedule(int nodes, int[] order, int[] position, int[] first, int[] last, int[][] dependencies, int[] switchOf) {
        this.nodes = nodes;
        this.order = order;
        this.position = position;
        this.first = first;
        this.last = last;
        this.dependencies = dependencies;
        this.switchOf = switchOf;
    }

    /** How many nodes run: those not culled. */
    public int size() {
        return order.length;
    }

    /** The node at {@code position}. */
    public int node(int position) {
        return order[position];
    }

    /** Where {@code node} runs, or -1 if it was culled. */
    public int position(int node) {
        if (node < 0 || node >= nodes) {
            throw new IllegalArgumentException("No node " + node + " of " + nodes);
        }
        return position[node];
    }

    /** The first position using {@code resource}, or -1 if no node running uses it. */
    public int first(int resource) {
        return first[resource];
    }

    /** The last position using {@code resource}, or -1 if no node running uses it. */
    public int last(int resource) {
        return last[resource];
    }

    /** How many resources the graph has. */
    public int resourceCount() {
        return first.length;
    }

    /** How many positions {@code position} depends on: earlier ones whose effects it must see, or not overwrite. */
    public int dependencyCount(int position) {
        return dependencies[position].length;
    }

    /** Dependency {@code i} of {@code position}, ascending: an earlier position. */
    public int dependency(int position, int i) {
        return dependencies[position][i];
    }

    /** The switch the node at {@code position} runs under, or {@link #ALWAYS}. */
    public int switchOf(int position) {
        return switchOf[position];
    }

    /**
     * The positions that run with the switches {@code on} (bit {@code i}: switch {@code i}), in order, into {@code out}
     * (at least {@link #size()} long); returns how many. Allocates nothing.
     */
    public int active(long on, int[] out) {
        int count = 0;
        for (int p = 0; p < order.length; p++) {
            if (switchOf[p] == ALWAYS || (on >>> switchOf[p] & 1) != 0) {
                out[count++] = p;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "Schedule" + Arrays.toString(order);
    }
}
