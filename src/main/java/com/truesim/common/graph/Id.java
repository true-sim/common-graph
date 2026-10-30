package com.truesim.common.graph;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A typed, namespaced identity: declared once as a constant next to what it names, and used by reference, so a misspelt
 * name does not compile. The name ({@code "truesim.bloom"}, {@code "acme.fog"}) exists for persistence and diagnostics
 * only; {@link IdRegistry} rejects two different ids of one name.
 *
 * <pre>{@code
 * public final class Bloom implements Technique {
 *     public static final Id<Bloom> ID = Id.of("truesim.bloom", Bloom.class);
 * }
 * view.without(Bloom.ID);
 * }</pre>
 *
 * <p>Ids compare by identity: two constants are the same id only if they are the same object.
 *
 * @param <T> what it identifies
 */
public final class Id<T> {

    /** A namespace and a name, each starting with a lowercase letter: {@code acme.fog}, {@code acme.weather.rain}. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+");

    private final String name;
    private final Class<T> kind;

    private Id(String name, Class<T> kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * The id {@code name} of something of {@code kind}.
     *
     * @throws IllegalArgumentException unless the name is a namespace and a name: {@code acme.fog}
     */
    public static <T> Id<T> of(String name, Class<T> kind) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("An id is a namespace and a name, such as acme.fog: " + name);
        }
        return new Id<>(name, kind);
    }

    /** The name, for persistence and diagnostics. */
    public String name() {
        return name;
    }

    /** What it identifies. */
    public Class<T> kind() {
        return kind;
    }

    @Override
    public String toString() {
        return name;
    }
}
