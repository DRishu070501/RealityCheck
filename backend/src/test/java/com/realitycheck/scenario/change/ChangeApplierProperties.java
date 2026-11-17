package com.realitycheck.scenario.change;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.scenario.SnapshotFixtures;
import com.realitycheck.scenario.change.ChangeApplier.Change;
import com.realitycheck.scenario.change.ChangePayload.CapacityMode;
import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/** Spec 06 design, "Testing": invariants of change application over generated change lists. */
class ChangeApplierProperties {

    private static final List<UUID> POOLS = List.of(SnapshotFixtures.SAWS, SnapshotFixtures.WHEELS,
            SnapshotFixtures.POLISHERS);

    @Property
    void capacityIsNeverNegativeAndTheInputIsNeverModified(@ForAll("changes") @Size(max = 8) List<ChangePayload> payloads) {
        Snapshot base = SnapshotFixtures.base();
        String before = SnapshotJson.hash(base);

        Snapshot changed = ChangeApplier.apply(base, numbered(payloads)).snapshot();

        for (PoolSnapshot pool : changed.pools()) {
            assertThat(pool.baseCapacity()).isNotNegative();
        }
        assertThat(SnapshotJson.hash(base)).isEqualTo(before);
        assertThat(changed.appliedChanges()).hasSize(payloads.size());
    }

    @Property
    void demandNeverAltersABatchThatHasStarted(@ForAll("changes") @Size(max = 8) List<ChangePayload> payloads) {
        Snapshot base = SnapshotFixtures.base();

        Snapshot changed = ChangeApplier.apply(base, numbered(payloads)).snapshot();

        for (BatchSnapshot batch : base.batches()) {
            if (!ChangeApplier.unstarted(batch, base)) {
                assertThat(SnapshotFixtures.batch(changed, batch.id())).isEqualTo(batch);
            }
        }
    }

    @Provide
    Arbitrary<List<ChangePayload>> changes() {
        Arbitrary<ChangePayload> capacity = Combinators.combine(
                Arbitraries.of(POOLS), Arbitraries.of(CapacityMode.values()), Arbitraries.integers().between(-150, 150))
                .as((pool, mode, value) -> {
                    int v = switch (mode) {
                        case ABSOLUTE -> Math.abs(value);
                        case DELTA -> value == 0 ? 1 : value;
                        case PERCENT -> value == 0 ? 10 : Math.max(-100, value);
                    };
                    return new ResourceCapacity(pool, mode, BigDecimal.valueOf(v));
                });
        Arbitrary<ChangePayload> demand = Arbitraries.integers().between(-99, 500).filter(p -> p != 0)
                .map(p -> new DemandVolume(BigDecimal.valueOf(p)));
        return Arbitraries.oneOf(capacity, demand).list();
    }

    private static List<Change> numbered(List<ChangePayload> payloads) {
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            changes.add(new Change(i + 1, payloads.get(i)));
        }
        return changes;
    }
}
