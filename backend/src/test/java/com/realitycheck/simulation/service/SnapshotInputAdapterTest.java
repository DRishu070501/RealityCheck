package com.realitycheck.simulation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.scenario.SnapshotFixtures;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationInput;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationResult;
import org.junit.jupiter.api.Test;

/** The snapshot-to-engine mapping is field for field (spec 07, design B.1). */
class SnapshotInputAdapterTest {

    @Test
    void mapsInstantsToEpochSecondsAndLocalTimesToSecondsOfDay() {
        Snapshot snapshot = SnapshotFixtures.base();

        SimulationInput input = SnapshotInputAdapter.toInput(snapshot);

        assertThat(input.forkTime()).isEqualTo(SnapshotFixtures.FORK.getEpochSecond());
        assertThat(input.calendar().zoneId()).isEqualTo("Asia/Kolkata");
        assertThat(input.calendar().shifts()).hasSize(6);
        assertThat(input.calendar().shifts().get(0).startSecondOfDay()).isEqualTo(9 * 3600);
        assertThat(input.calendar().shifts().get(0).endSecondOfDay()).isEqualTo(18 * 3600);
        assertThat(input.calendar().shifts().get(0).breaks().get(0).startSecondOfDay()).isEqualTo(13 * 3600);
        assertThat(input.stages()).extracting(SimulationInput.StageSpec::code).containsExactly("CUTTING", "POLISHING");
        assertThat(input.stages().get(1).requirements()).hasSize(2);
        assertThat(input.pools()).extracting(SimulationInput.PoolSpec::baseCapacity).containsExactly(4, 6, 12);
        assertThat(input.orders().get(0).dueTime())
                .isEqualTo(SnapshotFixtures.FORK.plusSeconds(5 * 86_400).getEpochSecond());
        assertThat(input.limits().maxSimulatedSeconds()).isEqualTo(180L * 24 * 3600);
        assertThat(input.bottleneck().saturationThreshold()).isEqualByComparingTo("0.50");

        BatchSpec inProgress = input.batches().get(0);
        assertThat(inProgress.state()).isEqualTo(BatchEntryState.IN_PROGRESS);
        assertThat(inProgress.startTime()).isEqualTo(SnapshotFixtures.FORK.minusSeconds(1800).getEpochSecond());
        assertThat(inProgress.queueEntryTime()).isNull();
        assertThat(input.batches().get(3).state()).isEqualTo(BatchEntryState.FUTURE_ARRIVAL);
        assertThat(input.batches().get(3).arrivalTime())
                .isEqualTo(SnapshotFixtures.FORK.plusSeconds(86_400).getEpochSecond());
    }

    @Test
    void theMappedInputRunsToCompletion() {
        SimulationResult result = new SimulationEngine().run(SnapshotInputAdapter.toInput(SnapshotFixtures.base()));

        assertThat(result.terminationReason()).isEqualTo(SimulationResult.TerminationReason.COMPLETED);
        assertThat(result.summary().ordersCompleted()).isEqualTo(1);
    }
}
