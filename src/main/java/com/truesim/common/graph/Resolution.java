package com.truesim.common.graph;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Producers resolved by the keys of their results, built lazily: requiring a key builds the producer that provides it,
 * then every producer that modifies it, in order, the first time it is required, so producers are built before what
 * needs their results, and producers nothing needs are never built. Requests (shared work, by equality) are built once.
 * Results are single owner: a modifier takes a key's result to change, and those requiring the key get its last version.
 *
 * <p>The frame graph resolves techniques by key this way; the module graph resolves game modules. What a producer
 * provides and modifies, and how it is built, the caller says through {@link Producers}: building calls back
 * {@link #require}, {@link #take} and {@link #put}.
 *
 * @param <K> keys, compared by identity
 * @param <P> producers
 */
@AllowAllocation("resolving a build, when it changes")
public final class Resolution<K, P> {

    /** What the caller knows of its producers. */
    public interface Producers<K, P> {

        /** The keys {@code producer} provides. */
        List<K> provides(P producer);

        /** The keys {@code producer} modifies. */
        List<K> modifies(P producer);

        /** Builds {@code producer}: it requires, takes and puts results through the resolution. */
        void build(P producer);

        /** {@code producer}'s name, for messages. */
        String name(P producer);
    }

    private final Producers<K, P> producers;
    private final String noun;
    private final Map<K, P> providers = new IdentityHashMap<>();
    private final Map<K, Object> provided = new IdentityHashMap<>(); // each key's current version
    private final Map<K, List<P>> modifiers = new IdentityHashMap<>(); // in order
    private final Map<K, Integer> applied = new IdentityHashMap<>(); // modifiers done, from the first
    private final Map<P, Set<K>> touched = new IdentityHashMap<>(); // what each modifier took
    private final Set<P> built = new HashSet<>();
    private final Deque<P> building = new ArrayDeque<>();
    private final Map<Object, Object> requests = new HashMap<>();
    private Object scope;

    /**
     * Resolution over {@code all} producers, a key's modifiers applied in {@code order} (stable: equal ones as given);
     * {@code noun} names producers in messages ("technique", "module").
     *
     * @throws IllegalStateException if two producers provide one key, or one both provides and modifies a key
     */
    public Resolution(List<P> all, Producers<K, P> producers, Comparator<? super P> order, String noun) {
        this.producers = Objects.requireNonNull(producers, "producers");
        if (noun.isEmpty()) {
            throw new IllegalArgumentException("Producers need a noun for messages");
        }
        this.noun = noun;
        for (P producer : all) {
            for (K key : producers.provides(producer)) {
                P other = providers.putIfAbsent(key, producer);
                if (other != null) {
                    throw new IllegalStateException("Two " + noun + "s provide " + key + ": " + producers.name(other)
                        + " and " + producers.name(producer));
                }
            }
            for (K key : producers.modifies(producer)) {
                if (producers.provides(producer).contains(key)) {
                    throw new IllegalStateException(producers.name(producer) + " both provides and modifies " + key);
                }
                modifiers.computeIfAbsent(key, k -> new ArrayList<>()).add(producer);
            }
        }
        for (List<P> chain : modifiers.values()) {
            chain.sort(order);
        }
    }

    /**
     * What is being built now: a producer, a request, or null (the caller itself). Results made in a scope belong to
     * it.
     */
    public Object scope() {
        return scope;
    }

    /** Whether a producer provides {@code key}. */
    public boolean has(K key) {
        return providers.containsKey(key);
    }

    /**
     * The result {@code key}: built by its provider, then by every modifier, the first time.
     *
     * @throws IllegalStateException if no producer provides it, or producers need each other's results in a cycle
     */
    public Object require(K key) {
        Object result = resolve(key, Integer.MAX_VALUE);
        if (result == null) {
            throw new IllegalStateException(missing(key));
        }
        return result;
    }

    /** The result {@code key}, or null if no producer provides it. */
    public Object optional(K key) {
        return resolve(key, Integer.MAX_VALUE);
    }

    /**
     * For the producer being built, a modifier of {@code key}: the result as the modifiers before it left it, now its
     * to change. It may also {@link #put} a new version.
     *
     * @throws IllegalStateException unless the producer being built lists {@code key} among the keys it modifies
     */
    @SuppressWarnings("unchecked")
    public Object take(K key) {
        if (scope == null || !building.contains(scope) || !producers.modifies((P) scope).contains(key)) {
            throw new IllegalStateException(scopeName() + " cannot modify " + key + ": only a " + noun
                + " listing it among the keys it modifies can");
        }
        P modifier = (P) scope;
        Object result = resolve(key, modifiers.get(key).indexOf(modifier));
        if (result == null) {
            throw new IllegalStateException(missing(key));
        }
        touched.computeIfAbsent(modifier, m -> new HashSet<>()).add(key);
        return result;
    }

    /**
     * Provides {@code key}: one the producer being built provides, or a new version of one it took.
     *
     * @throws IllegalStateException if the producer may not provide it, provides it twice, or a new version is another
     *     kind of result than the one taken
     */
    @SuppressWarnings("unchecked")
    public void put(K key, Object result) {
        Objects.requireNonNull(result, "result");
        if (scope != null && building.contains(scope) && touched.getOrDefault((P) scope, Set.of()).contains(key)) {
            if (result.getClass() != provided.get(key).getClass()) {
                throw new IllegalStateException(scopeName() + " provides " + key + " as another kind of result");
            }
            provided.put(key, result); // a new version
            return;
        }
        if (scope == null || !building.contains(scope) || !producers.provides((P) scope).contains(key)) {
            throw new IllegalStateException(scopeName() + " cannot provide " + key + ": only the " + noun
                + " listing it among the keys it provides can");
        }
        if (provided.putIfAbsent(key, result) != null) {
            throw new IllegalStateException(scopeName() + " provides " + key + " twice");
        }
    }

    /**
     * The result of {@code request}: built now by {@code build} if no equal request was built before, otherwise the
     * earlier result, so shared work runs once.
     *
     * @throws IllegalStateException if building it needs an equal request's result (a cycle)
     */
    @SuppressWarnings("unchecked")
    public <R> R request(Object request, Supplier<R> build) {
        Objects.requireNonNull(request, "request");
        if (requests.containsKey(request)) {
            Object result = requests.get(request);
            if (result == null) {
                throw new IllegalStateException("The request " + request + " needs its own result");
            }
            return (R) result;
        }
        requests.put(request, null); // in progress: an equal request inside it is a cycle
        Object outer = scope;
        scope = request;
        R result;
        try {
            result = Objects.requireNonNull(build.get(), "A request's result");
        } finally {
            scope = outer;
        }
        requests.put(request, result);
        return result;
    }

    /**
     * Builds {@code producer} now, if it was not built yet.
     *
     * @throws IllegalStateException if it did not provide every key it provides
     */
    public void build(P producer) {
        if (!built.add(producer)) {
            return;
        }
        Object outer = scope;
        building.push(producer);
        scope = producer;
        try {
            producers.build(producer);
        } finally {
            scope = outer;
            building.pop();
        }
        List<K> missing = new ArrayList<>();
        for (K key : producers.provides(producer)) {
            if (!provided.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(producers.name(producer) + " did not provide " + missing);
        }
        for (K key : touched.getOrDefault(producer, Set.of())) {
            applied.put(key, modifiers.get(key).indexOf(producer) + 1);
        }
    }

    /** {@code key} after its first {@code upTo} modifiers (all: its last version), or null without a provider. */
    private Object resolve(K key, int upTo) {
        P producer = providers.get(key);
        if (producer == null) {
            return null;
        }
        if (!provided.containsKey(key)) {
            enter(producer, key);
        }
        List<P> chain = modifiers.getOrDefault(key, List.of());
        int end = Math.min(upTo, chain.size());
        while (applied.getOrDefault(key, 0) < end) {
            int i = applied.getOrDefault(key, 0);
            P modifier = chain.get(i);
            if (!built.contains(modifier) || building.contains(modifier)) {
                enter(modifier, key); // one being built needs the version after itself: a cycle
            }
            if (applied.getOrDefault(key, 0) == i) {
                applied.put(key, i + 1); // built without taking the key: nothing to do for it
            }
        }
        return provided.get(key);
    }

    /** Builds {@code producer} for {@code key}, unless that closes a cycle. */
    private void enter(P producer, K key) {
        if (building.contains(producer)) {
            List<String> chain = new ArrayList<>();
            building.descendingIterator().forEachRemaining(p -> chain.add(producers.name(p)));
            chain.add(producers.name(producer));
            throw new IllegalStateException(capitalized(noun) + "s require each other's results in a cycle: "
                + String.join(" -> ", chain) + " (for " + key + ")");
        }
        build(producer);
    }

    private String missing(K key) {
        return "No " + noun + " provides " + key + (scope == null ? "" : ", which " + scopeName() + " requires");
    }

    @SuppressWarnings("unchecked")
    private String scopeName() {
        if (scope == null) {
            return "Nothing";
        }
        return building.contains(scope) ? producers.name((P) scope) : "request " + scope;
    }

    private static String capitalized(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
