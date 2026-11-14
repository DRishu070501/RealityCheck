package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.realitycheck.simulation.engine.InvalidSimulationInputException.Problem;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InputValidatorTest {

    @Test
    void aValidInputIsAccepted() {
        assertThatCode(() -> valid().run()).doesNotThrowAnyException();
    }

    @Test
    void negativeCapacityIsRejected() {
        InputBuilder factory = valid();
        factory.machines("Broken", -1, "10");

        assertThat(codes(factory.build())).contains(InputValidator.NEGATIVE_CAPACITY);
    }

    @Test
    void aRequirementForAPoolThatIsNotInTheInputIsRejected() {
        InputBuilder factory = valid();
        factory.stage("POLISHING", "6", 0, needs(new UUID(9, 9), 1));

        assertThat(codes(factory.build())).contains(InputValidator.UNKNOWN_POOL);
    }

    @Test
    void aRequirementLargerThanThePoolEverHasIsRejectedBecauseTheStageCouldNeverRun() {
        InputBuilder factory = valid();
        UUID polishers = factory.employees("Polishers", 1, "10");
        factory.stage("POLISHING", "6", 0, needs(polishers, 2));

        List<Problem> problems = problems(factory.build());

        assertThat(problems).extracting(Problem::code).containsExactly(InputValidator.REQUIREMENT_EXCEEDS_CAPACITY);
        assertThat(problems.get(0).message()).contains("POLISHING", "Polishers", "could never run");
    }

    @Test
    void aRequirementThatAWindowMakesPossibleIsAccepted() {
        InputBuilder factory = valid();
        UUID polishers = factory.employees("Polishers", 1, "10");
        factory.window(polishers, 5_000, 9_000, 1);
        factory.stage("POLISHING", "6", 0, needs(polishers, 2));

        assertThatCode(factory::run).doesNotThrowAnyException();
    }

    @Test
    void aPoolThatAWindowKeepsAtZeroForTheWholeHorizonCannotServeAStage() {
        InputBuilder factory = valid().limits(10_000, 1_000);
        UUID wheels = factory.machines("Wheels", 1, "10");
        factory.window(wheels, -100, 50_000, -1);
        factory.stage("POLISHING", "6", 0, needs(wheels, 1));

        assertThat(codes(factory.build())).containsExactly(InputValidator.REQUIREMENT_EXCEEDS_CAPACITY);
    }

    @Test
    void ratesYieldsSetupAndCostsMustBeInRange() {
        InputBuilder factory = valid();
        factory.stage("ZERO_RATE", "0", 0);
        factory.stage("NEGATIVE_SETUP", "5", -1);
        factory.stage("YIELD_ABOVE_ONE", "5", 0, "1.01", "1", "0");
        factory.stage("ZERO_YIELD", "5", 0, "1", "0", "0");
        factory.stage("NEGATIVE_COST", "5", 0, "1", "1", "-1");

        assertThat(codes(factory.build())).containsExactlyInAnyOrder(InputValidator.NON_POSITIVE_RATE,
                InputValidator.NEGATIVE_SETUP, InputValidator.INVALID_YIELD, InputValidator.INVALID_YIELD,
                InputValidator.NEGATIVE_COST);
    }

    @Test
    void aRequirementNeedsAtLeastOneUnitAndEachPoolOnce() {
        InputBuilder factory = valid();
        UUID wheels = factory.machines("Wheels", 4, "10");
        factory.stage("ZERO_UNITS", "5", 0, needs(wheels, 0));
        factory.stage("TWICE", "5", 0, needs(wheels, 1), needs(wheels, 2));

        assertThat(codes(factory.build())).containsExactly(InputValidator.INVALID_REQUIREMENT,
                InputValidator.INVALID_REQUIREMENT);
    }

    @Test
    void aWindowMustEndAfterItStarts() {
        InputBuilder factory = valid();
        UUID wheels = factory.machines("Wheels", 4, "10");
        factory.window(wheels, 500, 500, -1);

        assertThat(codes(factory.build())).containsExactly(InputValidator.INVALID_WINDOW);
    }

    @Test
    void workWithAnEmptyCalendarIsRejectedButAnEmptyFactoryIsNot() {
        CalendarSpec closed = new CalendarSpec("UTC", List.of());

        assertThat(codes(valid().calendar(closed).build())).containsExactly(InputValidator.EMPTY_CALENDAR);

        InputBuilder idle = InputBuilder.alwaysOpen().calendar(closed);
        idle.stage("CUTTING", "10", 0);
        assertThatCode(idle::run).doesNotThrowAnyException();
    }

    @Test
    void shiftsAndBreaksMustBePossibleAndTheZoneKnown() {
        CalendarSpec impossible = new CalendarSpec("Mars/Olympus", List.of(
                new ShiftSpec(1, 9 * 3600, 18 * 3600, List.of(new BreakSpec(8 * 3600, 10 * 3600))),
                new ShiftSpec(8, 0, 3600, List.of()),
                new ShiftSpec(2, 18 * 3600, 9 * 3600, List.of()),
                new ShiftSpec(3, 0, 86_400, List.of())));

        assertThat(codes(valid().calendar(impossible).build())).containsOnly(InputValidator.INVALID_CALENDAR)
                .hasSize(4);
    }

    @Test
    void stageSequencesMustBeExactlyOneToN() {
        SimulationInput base = valid().build();
        var first = base.stages().get(0);
        var gap = new SimulationInput.StageSpec(new UUID(2, 99), "LATER", 3, first.stonesPerHourPerSlot(), 0,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, List.of());
        List<SimulationInput.StageSpec> stages = new ArrayList<>(base.stages());
        stages.add(gap);

        assertThat(codes(withStages(base, stages))).containsExactly(InputValidator.INVALID_SEQUENCE);
    }

    @Test
    void workWithoutAnyStageIsRejected() {
        SimulationInput base = valid().build();

        assertThat(codes(withStages(base, List.of()))).contains(InputValidator.NO_STAGES);
    }

    @Test
    void batchesMustBelongToAnOrderInTheInputAndEveryOrderMustHaveWork() {
        SimulationInput base = valid().build();
        List<OrderSpec> orders = new ArrayList<>(base.orders());
        orders.add(new OrderSpec(new UUID(3, 50), "EMPTY", 3, null));
        List<BatchSpec> batches = new ArrayList<>(base.batches());
        batches.add(new BatchSpec(new UUID(4, 51), new UUID(3, 77), BatchEntryState.QUEUED, 1, BigDecimal.TEN,
                BigDecimal.TEN, 0L, null, null));

        assertThat(codes(new SimulationInput(base.forkTime(), base.calendar(), base.stages(), base.pools(), orders,
                batches, base.limits(), base.bottleneck())))
                .containsExactlyInAnyOrder(InputValidator.UNKNOWN_ORDER, InputValidator.ORDER_WITHOUT_BATCHES);
    }

    @Test
    void aBatchNeedsValidQuantitiesAStageInTheWorkflowAndTheTimeItsStateRequires() {
        SimulationInput base = valid().forkAt(1_000).build();
        UUID order = base.orders().get(0).id();
        List<BatchSpec> batches = new ArrayList<>(base.batches());
        batches.add(batch(10, order, BatchEntryState.QUEUED, 1, "-1", 0L, null, null));
        batches.add(batch(11, order, BatchEntryState.QUEUED, 7, "5", 0L, null, null));
        batches.add(batch(12, order, BatchEntryState.QUEUED, 1, "5", null, null, null));
        batches.add(batch(13, order, BatchEntryState.QUEUED, 1, "5", 2_000L, null, null));
        batches.add(batch(14, order, BatchEntryState.IN_PROGRESS, 1, "5", null, 2_000L, null));
        batches.add(batch(15, order, BatchEntryState.FUTURE_ARRIVAL, 1, "5", null, null, null));

        List<Problem> problems = problems(new SimulationInput(base.forkTime(), base.calendar(), base.stages(),
                base.pools(), base.orders(), batches, base.limits(), base.bottleneck()));

        assertThat(problems).extracting(Problem::code).containsOnly(InputValidator.INVALID_BATCH).hasSize(6);
    }

    @Test
    void duplicateIdsAreRejected() {
        SimulationInput base = valid().build();
        List<BatchSpec> batches = new ArrayList<>(base.batches());
        batches.add(base.batches().get(0));
        List<SimulationInput.PoolSpec> pools = new ArrayList<>(base.pools());
        pools.add(base.pools().get(0));

        assertThat(codes(new SimulationInput(base.forkTime(), base.calendar(), base.stages(), pools, base.orders(),
                batches, base.limits(), base.bottleneck())))
                .containsExactly(InputValidator.DUPLICATE_ID, InputValidator.DUPLICATE_ID);
    }

    @Test
    void limitsMustBePositive() {
        assertThat(codes(valid().limits(0, 10).build())).contains(InputValidator.INVALID_LIMITS);
        assertThat(codes(valid().limits(10, 0).build())).contains(InputValidator.INVALID_LIMITS);
    }

    @Test
    void everyProblemIsReportedTogether() {
        InputBuilder factory = valid();
        factory.machines("Broken", -1, "10");
        factory.stage("ZERO_RATE", "0", 0);
        factory.stage("POLISHING", "6", 0, needs(new UUID(9, 9), 1));

        InvalidSimulationInputException thrown = catchThrowableOfType(InvalidSimulationInputException.class,
                factory::run);

        assertThat(thrown.problems()).extracting(Problem::code).containsExactlyInAnyOrder(
                InputValidator.NEGATIVE_CAPACITY, InputValidator.NON_POSITIVE_RATE, InputValidator.UNKNOWN_POOL);
        assertThat(thrown.getMessage()).contains("Broken", "ZERO_RATE", "POLISHING");
    }

    private static InputBuilder valid() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        UUID saws = factory.machines("Saws", 2, "100");
        factory.stage("CUTTING", "3600", 0, needs(saws, 1));
        factory.job("ORD-1", "100");
        return factory;
    }

    private static SimulationInput withStages(SimulationInput base, List<SimulationInput.StageSpec> stages) {
        return new SimulationInput(base.forkTime(), base.calendar(), stages, base.pools(), base.orders(),
                base.batches(), base.limits(), base.bottleneck());
    }

    private static BatchSpec batch(long n, UUID order, BatchEntryState state, int stage, String stones, Long queued,
            Long started, Long arrives) {
        return new BatchSpec(new UUID(4, 100 + n), order, state, stage, new BigDecimal(stones),
                new BigDecimal(stones).abs(), queued, started, arrives);
    }

    private static List<Problem> problems(SimulationInput input) {
        InvalidSimulationInputException thrown = catchThrowableOfType(InvalidSimulationInputException.class,
                () -> new SimulationEngine().run(input));
        assertThat(thrown).as("the input should have been rejected").isNotNull();
        return thrown.problems();
    }

    private static List<String> codes(SimulationInput input) {
        return problems(input).stream().map(Problem::code).toList();
    }
}
