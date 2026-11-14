package com.realitycheck.scenario.change;

import static com.realitycheck.scenario.SnapshotFixtures.ARRIVING;
import static com.realitycheck.scenario.SnapshotFixtures.CUTTING;
import static com.realitycheck.scenario.SnapshotFixtures.FORK;
import static com.realitycheck.scenario.SnapshotFixtures.IN_PROGRESS;
import static com.realitycheck.scenario.SnapshotFixtures.POLISHERS;
import static com.realitycheck.scenario.SnapshotFixtures.QUEUED_FIRST;
import static com.realitycheck.scenario.SnapshotFixtures.QUEUED_SECOND;
import static com.realitycheck.scenario.SnapshotFixtures.SAWS;
import static com.realitycheck.scenario.SnapshotFixtures.WHEELS;
import static com.realitycheck.scenario.SnapshotFixtures.base;
import static com.realitycheck.scenario.SnapshotFixtures.batch;
import static com.realitycheck.scenario.SnapshotFixtures.pool;
import static com.realitycheck.scenario.SnapshotFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.scenario.change.ChangeApplier.Change;
import com.realitycheck.scenario.change.ChangeApplier.ChangeApplicationException;
import com.realitycheck.scenario.change.ChangeApplier.Result;
import com.realitycheck.scenario.change.ChangePayload.CapacityMode;
import com.realitycheck.scenario.change.ChangePayload.DemandVolume;
import com.realitycheck.scenario.change.ChangePayload.ProcessingRate;
import com.realitycheck.scenario.change.ChangePayload.RateMode;
import com.realitycheck.scenario.change.ChangePayload.ResourceCapacity;
import com.realitycheck.scenario.change.ChangePayload.ResourceDowntime;
import com.realitycheck.scenario.change.ChangePayload.ResourceUnavailable;
import com.realitycheck.scenario.change.ChangePayload.ScheduleMode;
import com.realitycheck.scenario.change.ChangePayload.Shift;
import com.realitycheck.scenario.change.ChangePayload.WorkingSchedule;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CapacityWindowSnapshot;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each change type with exact expected values (spec 06, requirement 4). */
class ChangeApplierTest {

    @Test
    void capacityByAbsoluteDeltaAndPercentRoundsDownAndNeverGoesBelowZero() {
        assertThat(capacityAfter(SAWS, CapacityMode.ABSOLUTE, "7")).isEqualTo(7);
        assertThat(capacityAfter(WHEELS, CapacityMode.DELTA, "2")).isEqualTo(8);
        assertThat(capacityAfter(WHEELS, CapacityMode.DELTA, "-10")).isZero();
        // 4 saws -30% = 2.8, rounded down to 2: capacity is never overstated.
        assertThat(capacityAfter(SAWS, CapacityMode.PERCENT, "-30")).isEqualTo(2);
        assertThat(capacityAfter(SAWS, CapacityMode.PERCENT, "-100")).isZero();
        assertThat(capacityAfter(POLISHERS, CapacityMode.PERCENT, "10")).isEqualTo(13);
    }

    @Test
    void aCapacityChangeThatRoundsToTheSameValueIsAppliedWithANoEffectWarning() {
        Result result = apply(new ResourceCapacity(SAWS, CapacityMode.PERCENT, new BigDecimal("10")));

        assertThat(pool(result.snapshot(), SAWS).baseCapacity()).isEqualTo(4);
        assertThat(result.warnings()).singleElement()
                .satisfies(w -> assertThat(w.code()).isEqualTo(ChangeApplier.NO_EFFECT));
    }

    @Test
    void unavailabilityDefaultsToTheForkAndTheEndOfTheHorizon() {
        Snapshot changed = apply(new ResourceUnavailable(POLISHERS, 5, null, null)).snapshot();

        assertThat(pool(changed, POLISHERS).baseCapacity()).isEqualTo(12);
        assertThat(pool(changed, POLISHERS).windows()).containsExactly(
                new CapacityWindowSnapshot(FORK, FORK.plusSeconds(180L * 24 * 3600), -5));
    }

    @Test
    void downtimeCancelsTheBaseCapacityAndAnyUnitsAddedInThePeriod() {
        var from = FORK.plusSeconds(3600);
        var to = FORK.plusSeconds(7200);
        Snapshot changed = ChangeApplier.apply(base(), List.of(
                new Change(1, new ResourceUnavailable(WHEELS, 1, FORK, to.plusSeconds(3600))),
                new Change(2, new ResourceDowntime(WHEELS, from, to)))).snapshot();

        // The earlier window is negative, so only the base capacity needs cancelling.
        assertThat(pool(changed, WHEELS).windows()).contains(new CapacityWindowSnapshot(from, to, -6));
    }

    @Test
    void demandScalesOnlyBatchesThatHaveNotStartedTheirFirstStageExactlyAtScaleFour() {
        Snapshot changed = apply(new DemandVolume(new BigDecimal("20"))).snapshot();

        assertThat(batch(changed, QUEUED_FIRST).stones()).isEqualByComparingTo("36.0000");
        assertThat(batch(changed, QUEUED_FIRST).carats()).isEqualByComparingTo("46.8000");
        // 33.3333 * 1.2 = 39.99996, half-even at scale 4.
        assertThat(batch(changed, ARRIVING).carats()).isEqualTo(new BigDecimal("40.0000"));
        assertThat(batch(changed, ARRIVING).stones()).isEqualTo(new BigDecimal("30.0000"));
        // In progress, or already past the first stage: untouched.
        assertThat(batch(changed, IN_PROGRESS).stones()).isEqualTo(new BigDecimal("40.0000"));
        assertThat(batch(changed, QUEUED_SECOND).stones()).isEqualTo(new BigDecimal("19.7000"));
    }

    @Test
    void demandIsNotRoundedToWholeStones() {
        Snapshot changed = apply(new DemandVolume(new BigDecimal("-15"))).snapshot();

        assertThat(batch(changed, QUEUED_FIRST).stones()).isEqualTo(new BigDecimal("25.5000"));
    }

    @Test
    void rateByAbsoluteValueOrPercentAtScaleFour() {
        assertThat(stage(apply(new ProcessingRate(CUTTING, RateMode.ABSOLUTE, new BigDecimal("15"))).snapshot(),
                CUTTING).stonesPerHour()).isEqualTo(new BigDecimal("15.0000"));
        assertThat(stage(apply(new ProcessingRate(CUTTING, RateMode.PERCENT, new BigDecimal("-12.5"))).snapshot(),
                CUTTING).stonesPerHour()).isEqualTo(new BigDecimal("10.5000"));
    }

    @Test
    void addedShiftsJoinTheCalendarInOrderAndReplacementSubstitutesIt() {
        Shift sunday = new Shift(7, LocalTime.of(10, 0), LocalTime.of(14, 0), null);
        Snapshot added = apply(new WorkingSchedule(ScheduleMode.ADD, List.of(sunday))).snapshot();
        Snapshot replaced = apply(new WorkingSchedule(ScheduleMode.REPLACE, List.of(sunday))).snapshot();

        assertThat(added.calendar().shifts()).hasSize(7);
        assertThat(added.calendar().shifts().get(6).dayOfWeek()).isEqualTo(7);
        assertThat(replaced.calendar().shifts()).singleElement()
                .satisfies(s -> assertThat(s.startTime()).isEqualTo(LocalTime.of(10, 0)));
    }

    @Test
    void anAddedShiftThatOverlapsAnExistingOneIsRejected() {
        Shift overlapping = new Shift(1, LocalTime.of(17, 0), LocalTime.of(20, 0), null);

        assertThatThrownBy(() -> apply(new WorkingSchedule(ScheduleMode.ADD, List.of(overlapping))))
                .isInstanceOf(ChangeApplicationException.class)
                .hasMessageContaining("overlap")
                .satisfies(ex -> assertThat(((ChangeApplicationException) ex).field()).isEqualTo("payload.shifts"));
    }

    @Test
    void aPoolOutsideTheSnapshotIsRejected() {
        assertThatThrownBy(() -> apply(new ResourceCapacity(CUTTING, CapacityMode.DELTA, BigDecimal.ONE)))
                .isInstanceOf(ChangeApplicationException.class)
                .satisfies(ex -> assertThat(((ChangeApplicationException) ex).field())
                        .isEqualTo("payload.resourceId"));
    }

    @Test
    void changesApplyInOrdinalOrderSoOrderMatters() {
        var plusTwo = new ResourceCapacity(SAWS, CapacityMode.DELTA, new BigDecimal("2"));
        var minusThirty = new ResourceCapacity(SAWS, CapacityMode.PERCENT, new BigDecimal("-30"));

        // Saws: (4 + 2) * 0.7 = 4.2 -> 4, and 4 * 0.7 = 2.8 -> 2, + 2 = 4: these happen to agree.
        // Polishers: (12 + 3) * 0.7 = 10.5 -> 10, but 12 * 0.7 = 8.4 -> 8, + 3 = 11.
        Snapshot first = ChangeApplier.apply(base(), List.of(new Change(1, plusTwo), new Change(2, minusThirty)))
                .snapshot();
        Snapshot second = ChangeApplier.apply(base(), List.of(new Change(2, plusTwo), new Change(1, minusThirty)))
                .snapshot();
        Snapshot polishersFirst = ChangeApplier.apply(base(), List.of(
                new Change(1, new ResourceCapacity(POLISHERS, CapacityMode.DELTA, new BigDecimal("3"))),
                new Change(2, new ResourceCapacity(POLISHERS, CapacityMode.PERCENT, new BigDecimal("-30")))))
                .snapshot();
        Snapshot polishersSecond = ChangeApplier.apply(base(), List.of(
                new Change(2, new ResourceCapacity(POLISHERS, CapacityMode.DELTA, new BigDecimal("3"))),
                new Change(1, new ResourceCapacity(POLISHERS, CapacityMode.PERCENT, new BigDecimal("-30")))))
                .snapshot();

        assertThat(pool(first, SAWS).baseCapacity()).isEqualTo(4);
        assertThat(pool(second, SAWS).baseCapacity()).isEqualTo(4);
        assertThat(pool(polishersFirst, POLISHERS).baseCapacity()).isEqualTo(10); // 15 * 0.7 = 10.5 -> 10
        assertThat(pool(polishersSecond, POLISHERS).baseCapacity()).isEqualTo(11); // 12 * 0.7 = 8.4 -> 8, + 3
        assertThat(first.appliedChanges()).extracting(Snapshot.AppliedChange::ordinal).containsExactly(1, 2);
    }

    @Test
    void appliedChangesRecordTypePayloadAndAPlainLanguageSummary() {
        Snapshot changed = apply(new ResourceCapacity(WHEELS, CapacityMode.DELTA, new BigDecimal("2"))).snapshot();

        assertThat(changed.appliedChanges()).singleElement().satisfies(applied -> {
            assertThat(applied.changeType()).isEqualTo("RESOURCE_CAPACITY");
            assertThat(applied.payload().path("resourceId").asText()).isEqualTo(WHEELS.toString());
            assertThat(applied.summary()).isEqualTo("Polishing wheels: capacity +2 units, 6 to 8 units");
        });
    }

    @Test
    void theInputSnapshotIsNeverModified() {
        Snapshot original = base();
        String before = SnapshotJson.hash(original);

        ChangeApplier.apply(original, List.of(
                new Change(1, new DemandVolume(new BigDecimal("50"))),
                new Change(2, new ResourceCapacity(WHEELS, CapacityMode.ABSOLUTE, new BigDecimal("1")))));

        assertThat(SnapshotJson.hash(original)).isEqualTo(before);
    }

    @Test
    void noChangesGiveAnEqualSnapshotAndHash() {
        Snapshot original = base();

        Snapshot same = ChangeApplier.apply(original, List.of()).snapshot();

        assertThat(same).isEqualTo(original);
        assertThat(SnapshotJson.hash(same)).isEqualTo(SnapshotJson.hash(original));
    }

    private static int capacityAfter(java.util.UUID poolId, CapacityMode mode, String value) {
        return pool(apply(new ResourceCapacity(poolId, mode, new BigDecimal(value))).snapshot(), poolId).baseCapacity();
    }

    private static Result apply(ChangePayload payload) {
        return ChangeApplier.apply(base(), List.of(new Change(1, payload)));
    }
}
