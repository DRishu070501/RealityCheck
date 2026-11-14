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
