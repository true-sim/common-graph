package com.truesim.common.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Producers are built when their keys are required, modifiers in order, requests once, results single owner. */
class ResolutionTest {

    /** A test producer: what it provides and modifies, its order among modifiers, and what building it does. */
    static final class Step {
        final String name;
        final List<String> provides;
        final List<String> modifies;
        final int order;
        Consumer<Resolution<String, Step>> body = r -> { };

        Step(String name, List<String> provides, List<String> modifies, int order) {
            this.name = name;
            this.provides = provides;
            this.modifies = modifies;
            this.order = order;
        }
    }

    private final List<String> log = new ArrayList<>();
    private Resolution<String, Step> resolution;

    private Resolution<String, Step> resolve(Step... steps) {
        resolution = new Resolution<>(List.of(steps), new Resolution.Producers<>() {
            @Override
            public List<String> provides(Step step) {
                return step.provides;
            }

            @Override
            public List<String> modifies(Step step) {
                return step.modifies;
            }

            @Override
            public void build(Step step) {
                log.add(step.name);
                step.body.accept(resolution);
            }

            @Override
            public String name(Step step) {
                return step.name;
            }
        }, Comparator.comparingInt(s -> s.order), "technique");
        return resolution;
    }

    // Keys are strings, compared by identity: these constants.
    static final String COLOR = "color";
    static final String DEPTH = "depth";
    static final String LIGHTS = "lights";

    @Test
    void requiringBuildsProvidersThenModifiersInOrder() {
        Step shade = new Step("shade", List.of(COLOR, DEPTH), List.of(), 0);
        shade.body = r -> {
            r.put(COLOR, "color v1");
            r.put(DEPTH, "depth");
        };
        Step tonemap = new Step("tonemap", List.of(), List.of(COLOR), 2);
        tonemap.body = r -> {
            assertThat(r.take(COLOR)).isEqualTo("color v2");
            r.put(COLOR, "color v3"); // a new version
        };
        Step bloom = new Step("bloom", List.of(), List.of(COLOR), 1);
        bloom.body = r -> {
            assertThat(r.take(COLOR)).isEqualTo("color v1");
            r.put(COLOR, "color v2");
        };
        Step unused = new Step("unused", List.of(LIGHTS), List.of(), 0);
        Resolution<String, Step> r = resolve(unused, tonemap, shade, bloom);
        assertThat(r.scope()).isNull();
        assertThat(r.has(COLOR)).isTrue();
        assertThat(r.has("other")).isFalse();
        assertThat(r.require(COLOR)).isEqualTo("color v3");
        assertThat(r.require(COLOR)).as("built once").isEqualTo("color v3");
        assertThat(r.require(DEPTH)).isEqualTo("depth");
        assertThat(log).containsExactly("shade", "bloom", "tonemap"); // never "unused"
        assertThat(r.optional("nobody's")).isNull();
        r.build(shade); // built already: nothing
        assertThat(log).hasSize(3);
    }

    @Test
    void modifiersThatTakeNothingAreSkipped() {
        Step shade = new Step("shade", List.of(COLOR), List.of(), 0);
        shade.body = r -> r.put(COLOR, "color");
        Step idle = new Step("idle", List.of(), List.of(COLOR), 1); // modifies, but takes nothing this build
        Step late = new Step("late", List.of(), List.of(COLOR), 2);
        late.body = r -> r.take(COLOR);
        resolve(shade, idle, late);
        assertThat(resolution.require(COLOR)).isEqualTo("color");
        assertThat(log).containsExactly("shade", "idle", "late");
    }

    @Test
    void modifiersBuiltAlreadyAreNotBuiltAgain() {
        Step shade = new Step("shade", List.of(COLOR), List.of(), 0);
        shade.body = r -> r.put(COLOR, "color");
        Step post = new Step("post", List.of(LIGHTS), List.of(COLOR), 1);
        post.body = r -> r.put(LIGHTS, "lights"); // built for LIGHTS first, never takes COLOR
        resolve(shade, post);
        assertThat(resolution.require(LIGHTS)).isEqualTo("lights");
        assertThat(resolution.require(COLOR)).isEqualTo("color");
        assertThat(log).containsExactly("post", "shade");
    }

    @Test
    void requestsAreBuiltOnceAndScoped() {
        Resolution<String, Step> r = resolve();
        List<Object> scopes = new ArrayList<>();
        assertThat(r.request("cull", () -> {
            scopes.add(r.scope());
            return "culled";
        })).isEqualTo("culled");
        assertThat(r.request("cull", () -> "again")).as("an equal request: the first result").isEqualTo("culled");
        assertThat(scopes).containsExactly("cull");
        assertThat(r.scope()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> r.request("loop", () -> r.request("loop", () -> "x")))
            .withMessage("The request loop needs its own result");
        assertThatNullPointerException().isThrownBy(() -> r.request("empty", () -> null))
            .withMessage("A request's result");
        assertThatNullPointerException().isThrownBy(() -> r.request(null, () -> "x"));
        assertThat(r.scope()).as("restored after a failure").isNull();
    }

    @Test
    void misuseIsReported() {
        assertThatIllegalStateException().isThrownBy(() -> resolve(new Step("a", List.of(COLOR), List.of(), 0),
            new Step("b", List.of(COLOR), List.of(), 0))).withMessage("Two techniques provide color: a and b");
        assertThatIllegalStateException().isThrownBy(() -> resolve(new Step("a", List.of(COLOR), List.of(COLOR), 0)))
            .withMessage("a both provides and modifies color");

        Resolution<String, Step> r = resolve(new Step("lazy", List.of(COLOR), List.of(), 0));
        assertThatIllegalStateException().isThrownBy(() -> r.require(COLOR)).withMessage("lazy did not provide [color]");
        assertThatIllegalStateException().isThrownBy(() -> r.require(DEPTH)).withMessage("No technique provides depth");

        Step needy = new Step("needy", List.of(COLOR), List.of(), 0);
        needy.body = res -> res.require(DEPTH);
        assertThatIllegalStateException().isThrownBy(() -> resolve(needy).require(COLOR))
            .withMessage("No technique provides depth, which needy requires");
        assertThatIllegalStateException()
            .isThrownBy(() -> resolve().request("cull", () -> resolution.require(DEPTH)))
            .withMessage("No technique provides depth, which request cull requires");

        assertThatIllegalStateException().isThrownBy(() -> resolve().take(COLOR))
            .withMessage("Nothing cannot modify color: only a technique listing it among the keys it modifies can");
        assertThatIllegalStateException().isThrownBy(() -> resolve().put(COLOR, "x"))
            .withMessage("Nothing cannot provide color: only the technique listing it among the keys it provides can");
        assertThatIllegalStateException().isThrownBy(() -> resolve().request("r", () -> {
            resolution.take(COLOR);
            return "x";
        })).withMessageStartingWith("request r cannot modify color");
        assertThatIllegalStateException().isThrownBy(() -> resolve().request("r", () -> {
            resolution.put(COLOR, "x");
            return "x";
        })).withMessageStartingWith("request r cannot provide color");
        assertThatNullPointerException().isThrownBy(() -> resolve().put(COLOR, null));

        Step stranger = new Step("stranger", List.of(LIGHTS), List.of(), 0);
        stranger.body = res -> res.take(COLOR);
        assertThatIllegalStateException().isThrownBy(() -> resolve(stranger).require(LIGHTS))
            .withMessageStartingWith("stranger cannot modify color");
        Step twice = new Step("twice", List.of(COLOR), List.of(), 0);
        twice.body = res -> {
            res.put(COLOR, "a");
            res.put(COLOR, "b");
        };
        assertThatIllegalStateException().isThrownBy(() -> resolve(twice).require(COLOR))
            .withMessage("twice provides color twice");
        Step other = new Step("other", List.of(LIGHTS), List.of(), 0);
        other.body = res -> res.put(COLOR, "c");
        assertThatIllegalStateException().isThrownBy(() -> resolve(other).require(LIGHTS))
            .withMessageStartingWith("other cannot provide color");

        Step base = new Step("base", List.of(COLOR), List.of(), 0);
        base.body = res -> res.put(COLOR, "text");
        Step kind = new Step("kind", List.of(), List.of(COLOR), 1);
        kind.body = res -> {
            res.take(COLOR);
            res.put(COLOR, 42);
        };
        assertThatIllegalStateException().isThrownBy(() -> resolve(base, kind).require(COLOR))
            .withMessage("kind provides color as another kind of result");

        Step orphan = new Step("orphan", List.of(LIGHTS), List.of(COLOR), 0);
        orphan.body = res -> {
            res.put(LIGHTS, "l");
            res.take(COLOR); // modifies a key no one provides
        };
        assertThatIllegalStateException().isThrownBy(() -> resolve(orphan).require(LIGHTS))
            .withMessage("No technique provides color, which orphan requires");
    }

    @Test
    void cyclesAreReported() {
        Step a = new Step("a", List.of(COLOR), List.of(), 0);
        a.body = r -> r.require(DEPTH);
        Step b = new Step("b", List.of(DEPTH), List.of(), 0);
        b.body = r -> r.require(COLOR);
        assertThatIllegalStateException().isThrownBy(() -> resolve(a, b).require(COLOR))
            .withMessage("Techniques require each other's results in a cycle: a -> b -> a (for color)");
        // A modifier requiring the key's last version: its own result, a cycle.
        Step shade = new Step("shade", List.of(COLOR), List.of(), 0);
        shade.body = r -> r.put(COLOR, "c");
        Step selfish = new Step("selfish", List.of(), List.of(COLOR), 1);
        selfish.body = r -> r.require(COLOR);
        assertThatIllegalStateException().isThrownBy(() -> resolve(shade, selfish).require(COLOR))
            .withMessage("Techniques require each other's results in a cycle: selfish -> selfish (for color)");
        // A noun is needed for messages.
        assertThatIllegalArgumentException().isThrownBy(() -> new Resolution<String, Step>(List.of(),
            new Resolution.Producers<>() {
            @Override
            public List<String> provides(Step step) {
                return List.of();
            }

            @Override
            public List<String> modifies(Step step) {
                return List.of();
            }

            @Override
            public void build(Step step) {
            }

            @Override
            public String name(Step step) {
                return step.name;
            }
        }, Comparator.comparingInt(s -> s.order), ""));
    }
}
