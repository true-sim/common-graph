package com.truesim.common.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** Ids are typed constants with namespaced names, unique per registry, resolved from data with suggestions. */
class IdTest {

    record Technique() {
    }

    record Module() {
    }

    static final Id<Technique> BLOOM = Id.of("truesim.bloom", Technique.class);
    static final Id<Technique> FOG = Id.of("acme.fog", Technique.class);
    static final Id<Module> WEATHER = Id.of("acme.weather.rain", Module.class);

    @Test
    void idsAreTypedAndNamespaced() {
        assertThat(BLOOM.name()).isEqualTo("truesim.bloom");
        assertThat(BLOOM.kind()).isEqualTo(Technique.class);
        assertThat(BLOOM).hasToString("truesim.bloom");
        assertThat(Id.of("truesim.bloom", Technique.class)).as("by identity").isNotEqualTo(BLOOM);
        for (String bad : new String[] {"bloom", "Truesim.bloom", "truesim.", ".bloom", "truesim..bloom", "truesim.9",
            "truesim.blo om", "acme-x.fog"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> Id.of(bad, Technique.class))
                .withMessageContaining("namespace and a name");
        }
        assertThatNullPointerException().isThrownBy(() -> Id.of(null, Technique.class));
        assertThatNullPointerException().isThrownBy(() -> Id.of("acme.x", null));
    }

    @Test
    void registriesKeepNamesUniqueAndResolveData() {
        IdRegistry registry = new IdRegistry();
        assertThat(registry.register(BLOOM)).isSameAs(BLOOM);
        registry.register(BLOOM); // again: nothing
        registry.register(FOG);
        registry.register(WEATHER);
        assertThat(registry.contains(BLOOM)).isTrue();
        assertThat(registry.contains(Id.of("truesim.bloom", Technique.class))).as("same name, other id").isFalse();
        assertThat(registry.contains(Id.of("acme.other", Technique.class))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> registry.register(Id.of("truesim.bloom", Module.class)))
            .withMessageContaining("Two ids are named truesim.bloom");
        assertThatNullPointerException().isThrownBy(() -> registry.register(null));

        assertThat(registry.resolve("acme.fog", Technique.class)).isSameAs(FOG);
        assertThat(registry.ids(Technique.class)).containsExactly(FOG, BLOOM);
        assertThat(registry.ids(Module.class)).containsExactly(WEATHER);
        // Misspelt: the closest name of that kind is suggested; a far one is not.
        assertThatIllegalArgumentException().isThrownBy(() -> registry.resolve("truesim.blom", Technique.class))
            .withMessage("No Technique is named truesim.blom: did you mean truesim.bloom?");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.resolve("acme.weather.rain", Technique.class))
            .withMessage("No Technique is named acme.weather.rain");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.resolve("x", Module.class))
            .withMessage("No Module is named x");
        IdRegistry empty = new IdRegistry();
        assertThatIllegalArgumentException().isThrownBy(() -> empty.resolve("acme.fog", Technique.class))
            .withMessage("No Technique is named acme.fog");
        // Equally close names: the first by name wins.
        IdRegistry ties = new IdRegistry();
        ties.register(Id.of("acme.fob", Technique.class));
        ties.register(Id.of("acme.fox", Technique.class));
        ties.register(Id.of("acme.foa", Technique.class));
        assertThatIllegalArgumentException().isThrownBy(() -> ties.resolve("acme.fog", Technique.class))
            .withMessageEndingWith("did you mean acme.foa?");
    }

    @Test
    void editDistancesCountEdits() {
        assertThat(IdRegistry.distance("", "abc")).isEqualTo(3);
        assertThat(IdRegistry.distance("abc", "")).isEqualTo(3);
        assertThat(IdRegistry.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(IdRegistry.distance("same", "same")).isZero();
    }
}
