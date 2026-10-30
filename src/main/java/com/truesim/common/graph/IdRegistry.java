package com.truesim.common.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ids in use, by name: registering checks that no other id has the name, so two plugins cannot claim one name, and
 * names read from data (a scene file, saved settings) resolve back to their ids, with a suggestion for a misspelt one.
 */
@AllowAllocation("registering ids, rarely")
public final class IdRegistry {

    private final Map<String, Id<?>> byName = new HashMap<>();

    /**
     * Registers {@code id}; registering it again does nothing.
     *
     * @throws IllegalStateException if another id has its name
     */
    public <T> Id<T> register(Id<T> id) {
        Objects.requireNonNull(id, "id");
        Id<?> other = byName.putIfAbsent(id.name(), id);
        if (other != null && other != id) {
            throw new IllegalStateException("Two ids are named " + id.name() + ": of " + other.kind().getName()
                + " and of " + id.kind().getName());
        }
        return id;
    }

    /** Whether {@code id} is registered. */
    public boolean contains(Id<?> id) {
        return byName.get(id.name()) == id;
    }

    /**
     * The registered id of {@code kind} named {@code name}, as data gives it.
     *
     * @throws IllegalArgumentException if there is none, naming the closest registered name of that kind
     */
    @SuppressWarnings("unchecked")
    public <T> Id<T> resolve(String name, Class<T> kind) {
        Id<?> id = byName.get(name);
        if (id != null && id.kind() == kind) {
            return (Id<T>) id;
        }
        String closest = null;
        int best = Integer.MAX_VALUE;
        for (Id<T> candidate : ids(kind)) { // by name: of equally close names, the first wins
            int distance = distance(name, candidate.name());
            if (distance < best) {
                best = distance;
                closest = candidate.name();
            }
        }
        throw new IllegalArgumentException("No " + kind.getSimpleName() + " is named " + name
            + (closest != null && best <= Math.max(2, name.length() / 3) ? ": did you mean " + closest + "?" : ""));
    }

    /** The ids of {@code kind}, sorted by name. */
    @SuppressWarnings("unchecked")
    public <T> List<Id<T>> ids(Class<T> kind) {
        List<Id<T>> ids = new ArrayList<>();
        for (Id<?> id : byName.values()) {
            if (id.kind() == kind) {
                ids.add((Id<T>) id);
            }
        }
        ids.sort((a, b) -> a.name().compareTo(b.name()));
        return ids;
    }

    /** The edit distance of {@code a} and {@code b}: insertions, deletions and substitutions. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
