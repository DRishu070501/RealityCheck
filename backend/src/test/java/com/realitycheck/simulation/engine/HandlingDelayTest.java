package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.HOUR;
import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.simulation.engine.SimulationInput.StageKind;
import com.realitycheck.simulation.engine.SimulationResult.OrderResult;
import com.realitycheck.simulation.engine.SimulationResult.PoolResult;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Handling delays at working stages (spec 11, task 8): after processing, a batch stays at the stage for a
 * measured time with its units already released, as packets in a real factory wait to be moved on.
 */
class HandlingDelayTest {

    private static final long[] FIVE = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

    /** One worker, two batches of one hour each, five hours of handling after each. */
    private static InputBuilder oneWorkerTwoBatches(boolean withHandling) {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID worker = factory.employees("Planners", 1, "100.00");
        UUID plan = factory.stage("PLAN", "10", 0, needs(worker, 1));
        if (withHandling) {
            factory.handling(plan, FIVE);
        }
        factory.job("ORD-1", "10");
        factory.job("ORD-2", "10");
        return factory;
    }

    @Test
    void theWorkerIsFreeAsSoonAsProcessingEndsAndTheBatchLeavesAfterHandling() {
        SimulationResult result = oneWorkerTwoBatches(true).run();
        long fork = result.forkTime();

        List<Long> starts = result.trace().stream().filter(e -> e.type() == TraceType.JOB_STARTED)
                .map(TraceEvent::time).toList();
        // The second batch starts at hour 1, when the first finishes processing, not at hour 6.
        assertThat(starts).containsExactly(fork, fork + HOUR);
        // Each leaves five hours after its processing ends: at hours 6 and 7.
        assertThat(result.orders()).extracting(OrderResult::completionTime)
                .containsExactlyInAnyOrder(fork + 6 * HOUR, fork + 7 * HOUR);
        assertThat(result.summary().handlingSeconds()).isEqualTo(10 * HOUR);
        // Handling is manufacturing time, not parking.
        assertThat(result.summary().batchLeadMedianSeconds()).isEqualTo(6 * HOUR);
        assertThat(result.summary().batchManufacturingMedianSeconds()).isEqualTo(6 * HOUR);
    }

    /** Handling uses no capacity and does not make the stage look busier to the bottleneck rules. */
    @Test
    void handlingChangesNeitherUtilizationNorTheStageSignals() {
        SimulationResult plain = oneWorkerTwoBatches(false).run();
        SimulationResult handled = oneWorkerTwoBatches(true).run();

        PoolResult plainPool = plain.pools().get(0);
        PoolResult handledPool = handled.pools().get(0);
        assertThat(handledPool.busyUnitSeconds()).isEqualTo(plainPool.busyUnitSeconds());
        assertThat(handled.stages().get(0).processingSeconds()).isEqualTo(plain.stages().get(0).processingSeconds());
        assertThat(handled.stages().get(0).waitingWorkingSeconds())
                .isEqualTo(plain.stages().get(0).waitingWorkingSeconds());
        assertThat(handled.stages().get(0).bottleneck()).isEqualTo(plain.stages().get(0).bottleneck());
    }

    @Test
    void theNextStageReceivesTheBatchOnlyAfterHandling() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID planners = factory.employees("Planners", 1, "100.00");
        UUID cutters = factory.employees("Cutters", 1, "100.00");
        UUID plan = factory.stage("PLAN", "10", 0, needs(planners, 1));
        UUID cut = factory.stage("CUT", "10", 0, needs(cutters, 1));
        factory.handling(plan, FIVE);
        factory.job("ORD-1", "10");

        SimulationResult result = factory.run();
        long fork = result.forkTime();

        assertThat(result.trace()).filteredOn(e -> e.type() == TraceType.JOB_QUEUED && cut.equals(e.stageId()))
                .extracting(TraceEvent::time).containsExactly(fork + 6 * HOUR);
        assertThat(result.orders()).extracting(OrderResult::completionTime).containsExactly(fork + 7 * HOUR);
    }

    @Test
    void batchesSpreadOverTheHandlingDecilesAndAlwaysGetTheSameOne() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID plan = factory.stage("PLAN", "3600", 0); // no requirements: every batch starts at once, takes 1 s
        factory.handling(plan, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 0; i < 200; i++) {
            factory.job("ORD-" + i, "1");
        }

        SimulationResult first = factory.run();

        assertThat(first.orders().stream().map(o -> (o.completionTime() - first.forkTime()) / HOUR).distinct())
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(factory.run()).isEqualTo(first);
    }

    @Test
    void handlingIsOnlyForWorkingStagesAndNeedsTenAscendingDelays() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID worker = factory.employees("Planners", 1, "100.00");
        UUID plan = factory.stage("PLAN", "10", 0, needs(worker, 1));
        UUID park = factory.holding("PARK", StageKind.PARKING, FIVE);
        factory.handling(plan, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1);
        factory.handling(park, FIVE);
        factory.job("ORD-1", "10");

        assertThatThrownBy(factory::run)
                .isInstanceOf(InvalidSimulationInputException.class)
                .satisfies(ex -> assertThat(((InvalidSimulationInputException) ex).problems())
                        .extracting(InvalidSimulationInputException.Problem::message)
                        .contains("Stage PLAN needs ten ascending, non-negative handling delays, or none",
                                "Stage PARK (PARKING) has handling delays; only working stages do"));
    }
}
