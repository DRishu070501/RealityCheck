package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.PoolResult;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.statistics.Statistics;

/**
 * The invariants of .kiro/steering/simulation-rules.md, section 13, checked on generated
 * factories. A factory is derived deterministically from a list of small integers ("genes"), so
 * jqwik can shrink a failing case: one to four stages, one to three pools, up to twelve batches
 * that are queued at some stage or arrive later, on an always-open or a working-week calendar,
 * sometimes with a capacity window.
 */
class SimulationEngineProperties {

    private static final String[] RATES = {"600", "1200", "3600", "7"};
    private static final String[] YIELDS = {"1", "0.9", "0.985", "0.333333"};

    @Property
    void theSameInputGivesAnEqualResultWhateverOrderItsListsAreIn(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes, @ForAll long shuffleSeed) {
        SimulationInput input = factory(genes, false).build();
        Random random = new Random(shuffleSeed);
        SimulationInput shuffled = new SimulationInput(input.forkTime(), input.calendar(),
                shuffle(input.stages(), random), shuffle(input.pools(), random), shuffle(input.orders(), random),
                shuffle(input.batches(), random), input.limits(), input.bottleneck());

        SimulationResult first = new SimulationEngine().run(input);

        assertThat(new SimulationEngine().run(input)).isEqualTo(first);
        assertThat(new SimulationEngine().run(shuffled)).isEqualTo(first);
    }

    @Property
    void everyRunTerminatesWithAllWorkDoneAndAllUnitsReleased(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();

        SimulationResult result = new SimulationEngine().run(input);

        assertThat(result.eventsProcessed()).isLessThanOrEqualTo(input.limits().maxEvents());
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(result.unfinished()).isEmpty();
        assertThat(result.summary().ordersCompleted()).isEqualTo(input.orders().size());
        Map<UUID, Integer> held = replayAllocations(input, result);
        assertThat(held.values()).allMatch(units -> units == 0);
    }

    @Property
    void aTinyEventLimitStopsTheRunCleanly(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes,
            @ForAll @IntRange(min = 1, max = 30) int maxEvents) {
        SimulationInput input = factory(genes, false).limits(400L * 86_400, maxEvents).build();

        SimulationResult result = new SimulationEngine().run(input);

        assertThat(result.eventsProcessed()).isLessThanOrEqualTo(maxEvents);
        if (result.terminationReason() != TerminationReason.COMPLETED) {
            assertThat(result.unfinished()).isNotEmpty();
        }
        replayAllocations(input, result);
    }

    @Property
    void poolsAreNeverAskedForMoreThanTheyHaveAndNeverGoNegative(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();

        // replayAllocations asserts both bounds at every start and completion.
        replayAllocations(input, new SimulationEngine().run(input));
    }

    @Property
    void whatEntersAStageEqualsWhatLeavesPlusWhatIsLost(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationResult result = factory(genes, false).run();

        for (StageResult stage : result.stages()) {
            assertThat(stage.outputStones().add(stage.lossStones())).isEqualByComparingTo(stage.inputStones());
            assertThat(stage.outputCarats().add(stage.lossCarats())).isEqualByComparingTo(stage.inputCarats());
            assertThat(stage.lossStones().signum()).isGreaterThanOrEqualTo(0);
            assertThat(stage.lossCarats().signum()).isGreaterThanOrEqualTo(0);
        }
        assertThat(result.summary().outputStones()).isLessThanOrEqualTo(result.summary().inputStones());
    }

    @Property
    void everyBatchPassesEachStageOnceInSequenceOrder(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();
        Map<UUID, Integer> sequenceOfStage = new TreeMap<>();
        input.stages().forEach(stage -> sequenceOfStage.put(stage.id(), stage.sequence()));

        Map<UUID, Integer> lastCompleted = new TreeMap<>();
        for (TraceEvent event : new SimulationEngine().run(input).trace()) {
            if (event.type() == TraceType.JOB_COMPLETED) {
                int sequence = sequenceOfStage.get(event.stageId());
                Integer previous = lastCompleted.put(event.batchId(), sequence);
                if (previous != null) {
                    assertThat(sequence).as("batch %s", event.batchId()).isEqualTo(previous + 1);
                }
            }
        }
        // Every batch ends at the last stage.
        assertThat(lastCompleted.values()).allMatch(sequence -> sequence == input.stages().size());
        assertThat(lastCompleted).hasSize(input.batches().size());
    }

    @Property
    void utilizationIsARatioAndNothingIsNegative(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationResult result = factory(genes, true).run();

        for (PoolResult pool : result.pools()) {
            if (pool.utilization() == null) {
                assertThat(pool.utilizationNullReason()).isEqualTo(PoolResult.NO_AVAILABLE_TIME);
                assertThat(pool.availableUnitSeconds()).isZero();
            } else {
                assertThat(pool.utilization()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            }
            assertThat(pool.idleUnitSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(pool.busyUnitSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(pool.cost().signum()).isGreaterThanOrEqualTo(0);
        }
        for (BigDecimal kind : new BigDecimal[] {result.summary().machineUtilization(),
                result.summary().employeeUtilization()}) {
            if (kind != null) {
                assertThat(kind).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            }
        }
        for (StageResult stage : result.stages()) {
            assertThat(stage.bottleneck().score()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(stage.bottleneck().blockedShare()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(stage.bottleneck().saturationSignal()).isBetween(stage.bottleneck().blockedShare(),
                    BigDecimal.ONE);
            assertThat(stage.waitingWorkingSeconds()).isBetween(0L, stage.waitingCalendarSeconds());
        }
        assertThat(result.summary().elapsedSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().workingSecondsInWindow()).isBetween(0L, result.summary().elapsedSeconds());
    }

    @Property
    void timeNeverRunsBackwardsAndWorkOnlyStartsInWorkingTime(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();
        WorkingCalendar calendar = new WorkingCalendar(input.calendar());

        long previous = input.forkTime();
        for (TraceEvent event : new SimulationEngine().run(input).trace()) {
            assertThat(event.time()).isGreaterThanOrEqualTo(previous);
            previous = event.time();
            if (event.type() == TraceType.JOB_STARTED) {
                assertThat(calendar.isWorking(event.time())).as("start at %s", event.time()).isTrue();
            }
        }
    }

    @Property
    void aFactoryWithNoWorkDoesNothingAndCostsNothing(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();
        SimulationInput idle = new SimulationInput(input.forkTime(), input.calendar(), input.stages(),
                input.pools(), List.of(), List.of(), input.limits(), input.bottleneck());

        SimulationResult result = new SimulationEngine().run(idle);

        assertThat(result.summary().processingSeconds()).isZero();
        assertThat(result.summary().waitingWorkingSeconds()).isZero();
        assertThat(result.summary().cost().machine()).isEqualByComparingTo("0");
        assertThat(result.summary().cost().total()).isEqualByComparingTo("0");
        assertThat(result.endTime()).isEqualTo(result.forkTime());
    }

    /**
     * Rule 13.10, in the case where it provably holds: one stage, one pool, every job with the
     * same priority and no due date, all waiting at the fork.
     */
    @Property
    void inASingleStageAddingAUnitNeverLengthensTheRunAndRemovingOneNeverShortensTheWait(
            @ForAll @IntRange(min = 1, max = 5) int units,
            @ForAll @Size(min = 1, max = 15) List<@IntRange(min = 1, max = 500) Integer> jobs) {
        SimulationResult fewer = singleStage(units, jobs).run();
        SimulationResult more = singleStage(units + 1, jobs).run();

        assertThat(more.summary().elapsedSeconds()).isLessThanOrEqualTo(fewer.summary().elapsedSeconds());
        assertThat(fewer.summary().waitingWorkingSeconds())
                .isGreaterThanOrEqualTo(more.summary().waitingWorkingSeconds());
    }

    /**
     * Across several stages with priorities, adding capacity is not guaranteed to shorten a
     * non-preemptive schedule. This reports how often it lengthened one; it asserts nothing,
     * because such a result is the dispatch rule's real consequence, not a defect.
     */
    @Property(tries = 300)
    void reportHowOftenAddedCapacityLengthensAMultiStageSchedule(
            @ForAll @Size(60) List<@IntRange(min = 0, max = 999) Integer> genes) {
        SimulationInput input = factory(genes, false).build();
        if (input.pools().isEmpty() || input.batches().isEmpty()) {
            Statistics.collect("no comparison");
            return;
        }
        List<SimulationInput.PoolSpec> bigger = new ArrayList<>();
        for (int i = 0; i < input.pools().size(); i++) {
            SimulationInput.PoolSpec pool = input.pools().get(i);
            bigger.add(i == 0 ? new SimulationInput.PoolSpec(pool.id(), pool.kind(), pool.name(),
                    pool.baseCapacity() + 1, pool.hourlyCost(), pool.windows()) : pool);
        }
        long before = new SimulationEngine().run(input).summary().elapsedSeconds();
        long after = new SimulationEngine().run(new SimulationInput(input.forkTime(), input.calendar(),
                input.stages(), bigger, input.orders(), input.batches(), input.limits(), input.bottleneck()))
                .summary().elapsedSeconds();

        Statistics.collect(after > before ? "longer with one more unit" : after < before ? "shorter" : "unchanged");
    }

    // ---- generators ----

    private static InputBuilder singleStage(int units, List<Integer> jobs) {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID pool = factory.machines("Saws", units, "100");
        factory.stage("CUTTING", "3600", 0, needs(pool, 1));
        for (int i = 0; i < jobs.size(); i++) {
            factory.job("ORD-" + i, String.valueOf(jobs.get(i)));
        }
        return factory;
    }

    /**
     * @param allowCapacityLoss whether a window may remove units. Kept off where a property
     *                          assumes a pool is never over-committed.
     */
    private static InputBuilder factory(List<Integer> genes, boolean allowCapacityLoss) {
        int[] cursor = {0};
        java.util.function.IntUnaryOperator next = bound -> genes.get(cursor[0]++ % genes.size()) % bound;

        InputBuilder factory = next.applyAsInt(2) == 0 ? InputBuilder.alwaysOpen() : InputBuilder.demoCalendar();
        long fork = factory.forkTime();

        int poolCount = 1 + next.applyAsInt(3);
        List<UUID> pools = new ArrayList<>();
        List<Integer> capacities = new ArrayList<>();
        for (int i = 0; i < poolCount; i++) {
            int capacity = 1 + next.applyAsInt(3);
            capacities.add(capacity);
            pools.add(i % 2 == 0 ? factory.machines("Machines " + i, capacity, "120")
                    : factory.employees("People " + i, capacity, "90"));
        }
        int windowKind = next.applyAsInt(4);
        if (windowKind == 1) {
            factory.window(pools.get(0), fork + 3_000, fork + 40_000, 1);
        } else if (windowKind == 2 && allowCapacityLoss) {
            factory.window(pools.get(0), fork + 3_000, fork + 40_000, -1);
        }

        int stageCount = 1 + next.applyAsInt(4);
        for (int s = 0; s < stageCount; s++) {
            List<RequirementSpec> requirements = new ArrayList<>();
            int first = next.applyAsInt(poolCount + 1);
            if (first < poolCount) {
                requirements.add(needs(pools.get(first), 1 + next.applyAsInt(capacities.get(first))));
                int second = next.applyAsInt(poolCount + 1);
                if (second < poolCount && second != first) {
                    requirements.add(needs(pools.get(second), 1 + next.applyAsInt(capacities.get(second))));
                }
            }
            factory.stage("STAGE_" + (s + 1), RATES[next.applyAsInt(RATES.length)], next.applyAsInt(4) * 100L,
                    YIELDS[next.applyAsInt(YIELDS.length)], YIELDS[next.applyAsInt(YIELDS.length)], "25",
                    requirements.toArray(RequirementSpec[]::new));
        }

        int batchCount = next.applyAsInt(13);
        UUID order = null;
        for (int b = 0; b < batchCount; b++) {
            if (order == null || next.applyAsInt(3) != 0) {
                Long due = next.applyAsInt(2) == 0 ? null : fork + 3_600L * (1 + next.applyAsInt(200));
                order = factory.order("ORD-" + b, 1 + next.applyAsInt(5), due);
            }
            String stones = String.valueOf(1 + next.applyAsInt(200));
            int stage = 1 + next.applyAsInt(stageCount);
            switch (next.applyAsInt(3)) {
                case 0 -> factory.queued(order, stage, stones);
                case 1 -> factory.queuedSince(order, stage, stones, fork - next.applyAsInt(5_000));
                default -> factory.arriving(order, stage, stones, fork + next.applyAsInt(300_000));
            }
        }
        return factory;
    }

    /**
     * Replays starts and completions against the pools, asserting at each step that no pool is
     * asked for more than it has then and that none goes negative.
     *
     * @return units still held at the end of the trace, by pool
     */
    private static Map<UUID, Integer> replayAllocations(SimulationInput input, SimulationResult result) {
        Map<UUID, StageSpec> stages = new TreeMap<>();
        input.stages().forEach(stage -> stages.put(stage.id(), stage));
        Map<UUID, CapacityProfile> profiles = new TreeMap<>();
        Map<UUID, Integer> held = new TreeMap<>();
        input.pools().forEach(pool -> {
            profiles.put(pool.id(), new CapacityProfile(pool.baseCapacity(), pool.windows()));
            held.put(pool.id(), 0);
        });
        for (TraceEvent event : result.trace()) {
            if (event.type() != TraceType.JOB_STARTED && event.type() != TraceType.JOB_COMPLETED) {
                continue;
            }
            for (RequirementSpec requirement : stages.get(event.stageId()).requirements()) {
                int change = event.type() == TraceType.JOB_STARTED ? requirement.unitsPerSlot()
                        : -requirement.unitsPerSlot();
                int now = held.merge(requirement.poolId(), change, Integer::sum);
                assertThat(now).as("units held of pool %s", requirement.poolId()).isGreaterThanOrEqualTo(0);
                if (event.type() == TraceType.JOB_STARTED) {
                    assertThat(now).as("units held of pool %s at %s", requirement.poolId(), event.time())
                            .isLessThanOrEqualTo(profiles.get(requirement.poolId()).capacityAt(event.time()));
                }
            }
        }
        return held;
    }

    private static <T> List<T> shuffle(List<T> list, Random random) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy, random);
        return copy;
    }
}
