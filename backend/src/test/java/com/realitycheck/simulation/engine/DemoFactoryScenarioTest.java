package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.DAY;
import static com.realitycheck.simulation.engine.InputBuilder.HOUR;
import static com.realitycheck.simulation.engine.InputBuilder.MONDAY_9AM;
import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.simulation.engine.SimulationResult.BottleneckResult;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The engine on a factory shaped like the demonstration one (same eight stages, rates, yields and
 * pool sizes, all synthetic), under the kinds of change the product's scenarios make. These are
 * the answers the product exists to give, so they are asserted directly.
 */
class DemoFactoryScenarioTest {

    private static final int ORDERS = 36;
    /** Demand, as a percentage of the base batch sizes, at which polishing cannot keep up. */
    private static final int LOADED = 120;
    /** Demand the factory can absorb. */
    private static final int LIGHT = 100;

    @Test
    void polishingIsTheBottleneckAndTheEvidenceSaysWhy() {
        SimulationResult baseline = factory(6, 12, 4, LOADED).run();
        StageResult polishing = stage(baseline, "POLISHING");

        assertThat(baseline.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(baseline.bottleneck().primaryStageId()).isEqualTo(polishing.stageId());
        assertThat(baseline.bottleneck().reasonCode()).isEqualTo(BottleneckResult.CONSTRAINED_POOL_WITH_QUEUE);
        assertThat(polishing.bottleneck().saturationSignal()).isGreaterThan(new BigDecimal("0.5"));
        assertThat(polishing.bottleneck().waitSignal()).isEqualByComparingTo("1");
        assertThat(polishing.maxQueueLength()).isGreaterThan(stage(baseline, "CUTTING").maxQueueLength());
        // Cutting takes real time too, but has room: slow is not the same as constrained.
        assertThat(stage(baseline, "CUTTING").bottleneck().score()).isLessThan(polishing.bottleneck().score());
    }

    @Test
    void atADemandTheFactoryCanAbsorbThereIsNoSustainedBottleneck() {
        SimulationResult light = factory(6, 12, 4, LIGHT).run();
        StageResult polishing = stage(light, "POLISHING");

        // Polishing still has the longest wait of any stage, but it is brief: naming it would mislead.
        assertThat(light.bottleneck().primaryStageId()).isNull();
        assertThat(light.bottleneck().reasonCode()).isEqualTo(BottleneckResult.NO_SUSTAINED_QUEUE);
        assertThat(polishing.bottleneck().waitSignal()).isEqualByComparingTo("1");
        assertThat(polishing.bottleneck().saturationSignal()).isLessThan(new BigDecimal("0.5"));
        assertThat(light.summary().ordersDelayed()).isZero();
    }

    @Test
    void addingWheelsAloneChangesNothingBecausePolishersAreTheBindingPool() {
        SimulationResult baseline = factory(6, 12, 4, LOADED).run();
        SimulationResult moreWheels = factory(8, 12, 4, LOADED).run();

        // One slot needs a wheel and two polishers: twelve polishers still allow only six slots.
        assertThat(moreWheels.summary().elapsedSeconds()).isEqualTo(baseline.summary().elapsedSeconds());
        assertThat(moreWheels.summary().waitingWorkingSeconds())
                .isEqualTo(baseline.summary().waitingWorkingSeconds());
        // The idle wheels are visible: wheel utilization falls while polishers stay saturated.
        assertThat(pool(moreWheels, "Polishing wheels").utilization())
                .isLessThan(pool(baseline, "Polishing wheels").utilization());
        assertThat(pool(moreWheels, "Polishers").utilization())
                .isEqualByComparingTo(pool(baseline, "Polishers").utilization());
    }

    @Test
    void addingWheelsWithThePolishersToWorkThemFinishesSoonerAndWaitsLess() {
        SimulationResult baseline = factory(6, 12, 4, LOADED).run();
        SimulationResult moreSlots = factory(8, 16, 4, LOADED).run();

        assertThat(moreSlots.summary().elapsedSeconds()).isLessThan(baseline.summary().elapsedSeconds());
        assertThat(stage(moreSlots, "POLISHING").waitingWorkingSeconds())
                .isLessThan(stage(baseline, "POLISHING").waitingWorkingSeconds());
        assertThat(moreSlots.summary().ordersDelayed()).isLessThanOrEqualTo(baseline.summary().ordersDelayed());
        // More people on the payroll for a shorter run: the cost is reported, not assumed lower.
        assertThat(moreSlots.summary().cost().total()).isNotNull();
        // The same stones come out either way: capacity changes time, not yield.
        assertThat(moreSlots.summary().outputStones()).isEqualByComparingTo(baseline.summary().outputStones());
    }

    @Test
    void losingFivePolishersLengthensTheRunAndTheQueue() {
        SimulationResult baseline = factory(6, 12, 4, LOADED).run();
        SimulationResult fewerPolishers = factory(6, 7, 4, LOADED).run();

        // Seven polishers work three wheels.
        assertThat(fewerPolishers.summary().elapsedSeconds()).isGreaterThan(baseline.summary().elapsedSeconds());
        assertThat(stage(fewerPolishers, "POLISHING").maxQueueLength())
                .isGreaterThanOrEqualTo(stage(baseline, "POLISHING").maxQueueLength());
        assertThat(fewerPolishers.summary().waitingWorkingSeconds())
                .isGreaterThan(baseline.summary().waitingWorkingSeconds());
    }

    @Test
    void twentyPercentMoreDemandTakesLongerThroughTheSameFactory() {
        SimulationResult baseline = factory(6, 12, 4, LOADED).run();
        SimulationResult moreDemand = factory(6, 12, 4, LOADED * 120 / 100).run();

        assertThat(moreDemand.summary().inputStones()).isGreaterThan(baseline.summary().inputStones());
        assertThat(moreDemand.summary().elapsedSeconds()).isGreaterThan(baseline.summary().elapsedSeconds());
        assertThat(moreDemand.summary().ordersDelayed()).isGreaterThanOrEqualTo(baseline.summary().ordersDelayed());
    }

    @Test
    void cuttingBecomesTheConstraintOncePolishingHasRoomAndCuttingLosesCapacity() {
        // Plenty of polishing, and cutting reduced from four saws to one.
        SimulationResult result = factory(12, 24, 1, LIGHT).run();

        assertThat(result.bottleneck().primaryStageId()).isEqualTo(stage(result, "CUTTING").stageId());
        assertThat(stage(result, "POLISHING").bottleneck().score())
                .isLessThan(stage(result, "CUTTING").bottleneck().score());
    }

    /**
     * @param wheels        polishing wheels
     * @param polishers     polishers; one polishing slot needs a wheel and two of them
     * @param saws          laser saws
     * @param demandPercent batch sizes as a percentage of the base sizes
     */
    private static InputBuilder factory(int wheels, int polishers, int saws, int demandPercent) {
        InputBuilder factory = InputBuilder.demoCalendar();
        UUID scanners = factory.machines("Planning scanners", 3, "220");
        UUID markingLasers = factory.machines("Laser marking units", 2, "180");
        UUID laserSaws = factory.machines("Laser saws", saws, "300");
        UUID lathes = factory.machines("Bruting lathes", 3, "160");
        UUID polishingWheels = factory.machines("Polishing wheels", wheels, "140");
        UUID microscopes = factory.machines("Grading microscopes", 3, "90");
        UUID intakeClerks = factory.employees("Intake clerks", 2, "110");
        UUID planners = factory.employees("Planners", 3, "260");
        UUID markers = factory.employees("Markers", 2, "190");
        UUID cutters = factory.employees("Cutters", 4, "240");
        UUID bruters = factory.employees("Bruters", 3, "210");
        UUID polisherPool = factory.employees("Polishers", polishers, "230");
        UUID inspectors = factory.employees("Quality inspectors", 3, "250");
        UUID dispatchClerks = factory.employees("Dispatch clerks", 2, "120");

        factory.stage("ROUGH_INTAKE", "240", 600, "1", "1", "50", needs(intakeClerks, 1));
        factory.stage("PLANNING", "30", 900, "1", "1", "120", needs(scanners, 1), needs(planners, 1));
        factory.stage("MARKING", "45", 600, "1", "1", "60", needs(markingLasers, 1), needs(markers, 1));
        factory.stage("CUTTING", "12", 1200, "0.985", "0.62", "200", needs(laserSaws, 1), needs(cutters, 1));
        factory.stage("BRUTING", "18", 900, "0.995", "0.90", "120", needs(lathes, 1), needs(bruters, 1));
        factory.stage("POLISHING", "6", 900, "0.99", "0.82", "150", needs(polishingWheels, 1),
                needs(polisherPool, 2));
        factory.stage("QUALITY_INSPECTION", "40", 300, "0.97", "1", "80", needs(microscopes, 1),
                needs(inspectors, 1));
        factory.stage("DISPATCH", "300", 600, "1", "1", "40", needs(dispatchClerks, 1));

        // Three orders each working day (09:00, 12:00, 16:00), Monday to Saturday, each due nine days later.
        long[] timesOfDay = {0, 3 * HOUR, 7 * HOUR};
        for (int i = 0; i < ORDERS; i++) {
            int workingDay = i / 3;
            long calendarDay = workingDay + workingDay / 6;
            long arrival = MONDAY_9AM + calendarDay * DAY + timesOfDay[i % 3];
            UUID order = factory.order("SDW-%04d".formatted(i + 1), 1 + i % 5, arrival + 9 * DAY);
            int stones = (60 + (i * 37 % 7) * 10) * demandPercent / 100;
            factory.arriving(order, 1, String.valueOf(stones), arrival);
        }
        return factory;
    }

    private static StageResult stage(SimulationResult result, String code) {
        return result.stages().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }

    private static SimulationResult.PoolResult pool(SimulationResult result, String name) {
        return result.pools().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
