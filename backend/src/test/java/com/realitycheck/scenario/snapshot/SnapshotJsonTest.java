package com.realitycheck.scenario.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.scenario.SnapshotFixtures;
import com.realitycheck.scenario.change.ChangeApplier;
import com.realitycheck.scenario.change.ChangeApplier.Change;
import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.service.ScenarioSnapshotService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Serialisation round trip and hash stability (spec 06, requirement 6). */
class SnapshotJsonTest {

    @Test
    void aSnapshotSurvivesARoundTripUnchanged() {
        Snapshot changed = ChangeApplier.apply(SnapshotFixtures.base(),
                List.of(new Change(1, new DemandVolume(new BigDecimal("20"))))).snapshot();

        Snapshot read = SnapshotJson.read(SnapshotJson.write(changed));

        // Payloads are JSON trees, where 20 may come back as an integer node rather than a decimal
        // node; the bytes and the hash, which is what reproducibility depends on, are identical.
        assertThat(SnapshotJson.write(read)).isEqualTo(SnapshotJson.write(changed));
        assertThat(SnapshotJson.hash(read)).isEqualTo(SnapshotJson.hash(changed));
        assertThat(read.batches()).isEqualTo(changed.batches());
        assertThat(read.stages()).isEqualTo(changed.stages());
        assertThat(read.pools()).isEqualTo(changed.pools());
    }

    @Test
    void aSnapshotWithoutRoutesSerialisesExactlyAsBeforeRoutesExisted() {
        String json = SnapshotJson.write(SnapshotFixtures.base());

        // No new keys: the bytes, and so the starting-state and input hashes of stored runs, are unchanged.
        assertThat(json).doesNotContain("\"routes\"").doesNotContain("\"routeCode\"")
                .doesNotContain("\"delayDeciles\"");
        // Pools have a kind (machine or employee); working stages do not.
        assertThat(SnapshotJson.read(json).stages()).allSatisfy(stage -> assertThat(stage.kind()).isNull());
        assertThat(json.split("\"pools\"")[0]).doesNotContain("\"kind\"");
        // JSON written before routes existed reads back with no routes.
        Snapshot read = SnapshotJson.read(json);
        assertThat(read.routes()).isEmpty();
        assertThat(read.batches()).allSatisfy(b -> assertThat(b.routeCode()).isNull());
    }

    @Test
    void routesSurviveARoundTripAndEveryScenarioChange() {
        Snapshot base = SnapshotFixtures.base();
        Snapshot.BatchSnapshot first = base.batches().get(0);
        java.util.List<Snapshot.BatchSnapshot> batches = new java.util.ArrayList<>(base.batches());
        batches.set(0, new Snapshot.BatchSnapshot(first.id(), first.orderId(), first.batchNumber(), first.state(),
                first.stageSequence(), first.stones(), first.carats(), first.queuedAt(), first.startedAt(),
                first.arrivalAt(), "TOPS"));
        Snapshot routed = new Snapshot(base.schemaVersion(), base.factoryId(), base.timeZone(), base.currencyCode(),
                base.forkAt(), base.calendar(), base.stages(), base.pools(), base.orders(), batches, base.limits(),
                base.bottleneck(), base.appliedChanges(),
                List.of(new Snapshot.RouteSnapshot("TOPS", "Tops", List.of(new Snapshot.RouteStepSnapshot(1, 100),
                        new Snapshot.RouteStepSnapshot(2, 50)))));

        Snapshot read = SnapshotJson.read(SnapshotJson.write(routed));
        assertThat(read.routes()).isEqualTo(routed.routes());
        assertThat(read.batches().get(0).routeCode()).isEqualTo("TOPS");
        assertThat(SnapshotJson.hash(read)).isEqualTo(SnapshotJson.hash(routed)).isNotEqualTo(SnapshotJson.hash(base));

        Snapshot changed = ChangeApplier.apply(routed,
                List.of(new Change(1, new DemandVolume(new BigDecimal("20"))))).snapshot();
        assertThat(changed.routes()).isEqualTo(routed.routes());
        assertThat(changed.batches().get(0).routeCode()).isEqualTo("TOPS");
    }

    @Test
    void aHoldingStageKeepsItsKindAndDelaysThroughARoundTrip() {
        Snapshot base = SnapshotFixtures.base();
        Snapshot.StageSnapshot cut = base.stages().get(0);
        Snapshot.StageSnapshot parked = new Snapshot.StageSnapshot(cut.id(), cut.code(), cut.name(), cut.sequence(),
                cut.stonesPerHour(), cut.setupSeconds(), cut.stoneYield(), cut.caratYield(), cut.fixedCostPerJob(),
                List.of(), "PARKING", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
        Snapshot.StageSnapshot working = new Snapshot.StageSnapshot(cut.id(), cut.code(), cut.name(), cut.sequence(),
                cut.stonesPerHour(), cut.setupSeconds(), cut.stoneYield(), cut.caratYield(), cut.fixedCostPerJob(),
                cut.requirements(), "WORKING", null);

        Snapshot.StageSnapshot read = SnapshotJson.read(SnapshotJson.write(
                new Snapshot(base.schemaVersion(), base.factoryId(), base.timeZone(), base.currencyCode(),
                        base.forkAt(), base.calendar(), List.of(parked, base.stages().get(1)), base.pools(),
                        base.orders(), base.batches(), base.limits(), base.bottleneck(), base.appliedChanges(),
                        base.routes()))).stages().get(0);

        assertThat(read).isEqualTo(parked);
        // WORKING is stored as absent, so it equals a stage written before kinds existed.
        assertThat(working).isEqualTo(cut);
    }

    @Test
    void theHashIsSixtyFourHexCharactersAndStableAcrossInstances() {
        String first = SnapshotJson.hash(SnapshotFixtures.base());

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(SnapshotJson.hash(SnapshotFixtures.base())).isEqualTo(first);
    }

    @Test
    void theInputHashChangesWithAnyChangeOrTheAlgorithmVersion() {
        Snapshot base = SnapshotFixtures.base();
        Snapshot changed = ChangeApplier.apply(base, List.of(new Change(1, new DemandVolume(BigDecimal.TEN))))
                .snapshot();

        String reference = ScenarioSnapshotService.inputHash(base, "1.0.0");
        assertThat(ScenarioSnapshotService.inputHash(base, "1.0.0")).isEqualTo(reference);
        assertThat(ScenarioSnapshotService.inputHash(changed, "1.0.0")).isNotEqualTo(reference);
        assertThat(ScenarioSnapshotService.inputHash(base, "1.0.1")).isNotEqualTo(reference);
    }
}
