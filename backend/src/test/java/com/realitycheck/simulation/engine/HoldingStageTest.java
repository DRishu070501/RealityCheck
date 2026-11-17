package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.HOUR;
import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.simulation.engine.SimulationInput.StageKind;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.SummaryMetrics;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Parking, waiting and job-work stages (spec 11, task 7): measured delays, no capacity. */
class HoldingStageTest {

    private static final long[] SAME_DELAY = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

    /** Every batch is held for exactly the delay, however many arrive together: nothing queues. */
    @Test
    void aHoldingStageHoldsEveryBatchForItsDelayWithoutCapacity() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        factory.holding("PARK", StageKind.PARKING, SAME_DELAY);
        for (int i = 0; i < 25; i++) {
            factory.job("ORD-" + i, "10");
        }

        SimulationResult result = factory.run();

        assertThat(result.endTime() - result.forkTime()).isEqualTo(5 * HOUR);
        StageResult park = result.stages().get(0);
        assertThat(park.jobsCompleted()).isEqualTo(25);
        assertThat(park.processingSeconds()).isZero();
        assertThat(park.waitingWorkingSeconds()).isZero();
        assertThat(park.maxQueueLength()).isZero();
        assertThat(result.summary().parkingSeconds()).isEqualTo(25 * 5 * HOUR);
        assertThat(result.summary().cost().total()).isEqualByComparingTo("0");
    }

    /** Delays are calendar time: a factory closed overnight does not stop parking from counting. */
    @Test
    void holdingDelaysRunInCalendarTimeAcrossClosedHours() {
        InputBuilder factory = InputBuilder.demoCalendar();
        factory.holding("PARK", StageKind.PARKING, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20);
        factory.job("ORD-1", "10");

        SimulationResult result = factory.run();

        assertThat(result.endTime() - result.forkTime()).isEqualTo(20 * HOUR);
    }

    @Test
    void batchesSpreadOverTheMeasuredDecilesAndAlwaysGetTheSameOne() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        factory.holding("WAIT", StageKind.WAITING, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 0; i < 200; i++) {
            factory.job("ORD-" + i, "1");
        }

        SimulationResult first = factory.run();
        List<Long> finished = first.trace().stream()
                .filter(e -> e.type() == TraceType.JOB_COMPLETED)
                .map(e -> (e.time() - first.forkTime()) / HOUR)
                .distinct()
                .toList();

        assertThat(finished).hasSizeGreaterThanOrEqualTo(8).allMatch(h -> h >= 1 && h <= 10);
        assertThat(factory.run()).isEqualTo(first);
        assertThat(first.summary().waitingStageSeconds()).isPositive();
    }

    /** Lead time counts parking; manufacturing lead time leaves it out. Job work counts as manufacturing. */
    @Test
    void manufacturingLeadTimeLeavesOutParkingButKeepsWaitingAndJobWork() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        factory.holding("PARK", StageKind.PARKING, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10);
        UUID workers = factory.employees("Cutters", 5, "100.00");
        factory.stage("CUT", "10", 0, needs(workers, 1)); // 10 stones at 10 per hour: one hour
        factory.holding("JOB", StageKind.JOB_WORK, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3);
        factory.holding("DISPATCH", StageKind.WAITING, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        factory.job("ORD-1", "10");

        SummaryMetrics summary = factory.run().summary();

        assertThat(summary.batchLeadMedianSeconds()).isEqualTo(16 * HOUR);
        assertThat(summary.batchManufacturingMedianSeconds()).isEqualTo(6 * HOUR);
        assertThat(summary.parkingSeconds()).isEqualTo(10 * HOUR);
        assertThat(summary.jobWorkSeconds()).isEqualTo(3 * HOUR);
        assertThat(summary.waitingStageSeconds()).isEqualTo(2 * HOUR);
    }

    /** Work already parked before the fork leaves when its delay, counted from when it was parked, is up. */
    @Test
    void aBatchParkedBeforeTheForkLeavesWhenItsDelayIsUp() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        factory.holding("PARK", StageKind.PARKING, SAME_DELAY);
        long fork = factory.forkTime();
        factory.queuedSince(factory.order("ORD-1"), 1, "10", fork - 3 * HOUR);
        factory.inProgress(factory.order("ORD-2"), 1, "10", fork - 8 * HOUR);

        SimulationResult result = factory.run();

        assertThat(result.orders()).extracting(SimulationResult.OrderResult::completionTime)
                .containsExactlyInAnyOrder(fork + 2 * HOUR, fork);
        // Only time inside the run is counted: 2 hours and 0.
        assertThat(result.summary().parkingSeconds()).isEqualTo(2 * HOUR);
    }

    @Test
    void withOnlyWorkingStagesTheNewMeasuresAreZeroAndLeadIsFromEntryToFinish() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID workers = factory.employees("Cutters", 1, "100.00");
        factory.stage("CUT", "10", 0, needs(workers, 1));
        factory.job("ORD-1", "10");
        factory.job("ORD-2", "10");

        SummaryMetrics summary = factory.run().summary();

        assertThat(summary.parkingSeconds()).isZero();
        assertThat(summary.waitingStageSeconds()).isZero();
        assertThat(summary.jobWorkSeconds()).isZero();
        // One worker: the batches finish after 1 and 2 hours; the lower middle of two is 1 hour.
        assertThat(summary.batchLeadMedianSeconds()).isEqualTo(HOUR);
        assertThat(summary.batchManufacturingMedianSeconds()).isEqualTo(HOUR);
    }

    @Test
    void holdingStagesNeedTenAscendingDelaysAndNoPools() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID workers = factory.employees("Cutters", 1, "100.00");
        factory.holding("SHORT", StageKind.PARKING, 1, 2, 3);
        factory.holding("DOWN", StageKind.WAITING, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
        factory.stage("CUT", "10", 0, needs(workers, 1));
        factory.job("ORD-1", "10");
        SimulationInput input = factory.build();
        SimulationInput.StageSpec cut = input.stages().get(2);
        SimulationInput withPooledHolding = new SimulationInput(input.forkTime(), input.calendar(),
                List.of(input.stages().get(0), input.stages().get(1), new SimulationInput.StageSpec(cut.id(),
                        cut.code(), cut.sequence(), cut.stonesPerHourPerSlot(), cut.setupSeconds(), cut.stoneYield(),
                        cut.caratYield(), cut.fixedCostPerJob(), cut.requirements(), StageKind.JOB_WORK,
                        List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L))),
                input.pools(), input.orders(), input.batches(), input.limits(), input.bottleneck());

        assertThatThrownBy(() -> new SimulationEngine().run(withPooledHolding))
                .isInstanceOf(InvalidSimulationInputException.class)
                .satisfies(ex -> assertThat(((InvalidSimulationInputException) ex).problems())
                        .extracting(InvalidSimulationInputException.Problem::message)
                        .contains("Stage SHORT (PARKING) needs ten ascending, non-negative delays",
                                "Stage DOWN (WAITING) needs ten ascending, non-negative delays",
                                "Stage CUT (JOB_WORK) holds batches without capacity, so it cannot require pools"));
    }
}
