package com.truesim.common.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Snapshots hold typed entries; equal snapshots hash alike, whatever order their entries came in. */
class SnapshotTest {

    record MaterialTypes(List<String> names) {
    }

    @SuppressWarnings("rawtypes")
    static final Id<List> FEATURES = Id.of("truesim.deviceFeatures", List.class);
    static final Id<MaterialTypes> TYPES = Id.of("truesim.materialTypes", MaterialTypes.class);
    static final Id<String> MISSING = Id.of("truesim.missing", String.class);

    @Test
    void entriesAreTypedAndReadBack() {
        Snapshot snapshot = Snapshot.builder().put(TYPES, new MaterialTypes(List.of("rock")))
            .put(FEATURES, List.of("meshShaders")).build();
        assertThat(snapshot.get(TYPES).names()).containsExactly("rock");
        assertThat(snapshot.has(TYPES)).isTrue();
        assertThat(snapshot.has(MISSING)).isFalse();
        assertThat(snapshot.size()).isEqualTo(2);
        assertThat(snapshot).hasToString("Snapshot[truesim.deviceFeatures, truesim.materialTypes]");
        assertThat(Snapshot.EMPTY.size()).isZero();
        assertThat(Snapshot.EMPTY).hasToString("Snapshot[]");
        assertThatIllegalArgumentException().isThrownBy(() -> snapshot.get(MISSING))
            .withMessage("The snapshot has no truesim.missing");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void misuseIsChecked() {
        Snapshot.Builder builder = Snapshot.builder().put(TYPES, new MaterialTypes(List.of()));
        assertThatIllegalStateException().isThrownBy(() -> builder.put(TYPES, new MaterialTypes(List.of())))
            .withMessage("The snapshot has truesim.materialTypes already");
        assertThatNullPointerException().isThrownBy(() -> builder.put(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> builder.put(MISSING, null));
        Id raw = TYPES;
        assertThatThrownBy(() -> builder.put(raw, "not material types")).isInstanceOf(ClassCastException.class);
    }

    @Test
    void equalSnapshotsHashAlike() {
        Snapshot a = Snapshot.builder().put(TYPES, new MaterialTypes(List.of("rock"))).put(MISSING, "x").build();
        Snapshot b = Snapshot.builder().put(MISSING, "x").put(TYPES, new MaterialTypes(List.of("rock"))).build();
        Snapshot c = Snapshot.builder().put(TYPES, new MaterialTypes(List.of("water"))).put(MISSING, "x").build();
        Snapshot d = Snapshot.builder().put(TYPES, new MaterialTypes(List.of("rock"))).build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.hash()).isEqualTo(b.hash());
        assertThat(a).isNotEqualTo(c).isNotEqualTo(d).isNotEqualTo("a snapshot");
        Snapshot sameHash = new SnapshotTest.Colliding().snapshot();
        assertThat(sameHash).isNotEqualTo(Snapshot.builder().put(MISSING, "Aa").build()); // equal hashes, other values
    }

    /** Two values with equal string hashes ("Aa" and "BB"), so equality must look past the hash. */
    static final class Colliding {
        Snapshot snapshot() {
            return Snapshot.builder().put(MISSING, "BB").build();
        }
    }
}
