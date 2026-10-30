package com.truesim.common.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a build may read, taken at once: an open set of typed entries (views, material types, device features, …), each
 * under its {@link Id}. Builds read only their snapshot, never live state, so a build on another thread sees one
 * consistent state, and two snapshots that are {@link #equals equal} build the same graph: their {@link #hash()} keys
 * caches of what builds make.
 *
 * <pre>{@code
 * static final Id<MaterialTypes> MATERIAL_TYPES = Id.of("truesim.materialTypes", MaterialTypes.class);
 * Snapshot snapshot = Snapshot.builder().put(MATERIAL_TYPES, materials.types()).build();
 * MaterialTypes types = snapshot.get(MATERIAL_TYPES);
 * }</pre>
 *
 * <p>Entries should be immutable values with {@code equals} and {@code hashCode}.
 */
@AllowAllocation("taking a snapshot, when a build is due")
public final class Snapshot {

    /** The snapshot with no entries. */
    public static final Snapshot EMPTY = builder().build();

    private final Map<Id<?>, Object> entries;
    private final int hash;

    private Snapshot(Map<Id<?>, Object> entries) {
        this.entries = Collections.unmodifiableMap(entries);
        int h = 0;
        for (Map.Entry<Id<?>, Object> entry : entries.entrySet()) {
            h += entry.getKey().name().hashCode() ^ entry.getValue().hashCode(); // in no order
        }
        this.hash = h;
    }

    /** Starts a snapshot. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The entry {@code id}.
     *
     * @throws IllegalArgumentException if the snapshot has none: the build reads what was not taken
     */
    public <T> T get(Id<T> id) {
        Object value = entries.get(id);
        if (value == null) {
            throw new IllegalArgumentException("The snapshot has no " + id);
        }
        return id.kind().cast(value);
    }

    /** Whether the snapshot has the entry {@code id}. */
    public boolean has(Id<?> id) {
        return entries.containsKey(id);
    }

    /** How many entries it has. */
    public int size() {
        return entries.size();
    }

    /** A hash of the entries' names and values, the same for equal snapshots: for keys of what builds make. */
    public int hash() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Snapshot snapshot && hash == snapshot.hash && entries.equals(snapshot.entries);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Snapshot[");
        entries.keySet().stream().map(Id::name).sorted().forEach(name -> text.append(text.length() > 9 ? ", " : "")
            .append(name));
        return text.append(']').toString();
    }

    /** Collects a snapshot's entries. */
    @AllowAllocation("taking a snapshot, when a build is due")
    public static final class Builder {
        private final Map<Id<?>, Object> entries = new HashMap<>(); // ids compare by identity

        private Builder() {
        }

        /**
         * Adds the entry {@code id}.
         *
         * @throws IllegalStateException if it has one already
         */
        public <T> Builder put(Id<T> id, T value) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");
            if (entries.putIfAbsent(id, id.kind().cast(value)) != null) {
                throw new IllegalStateException("The snapshot has " + id + " already");
            }
            return this;
        }

        public Snapshot build() {
            return new Snapshot(new HashMap<>(entries));
        }
    }
}
