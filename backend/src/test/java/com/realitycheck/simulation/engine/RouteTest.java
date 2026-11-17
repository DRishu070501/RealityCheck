package com.realitycheck.simulation.engine;

import static com.realitycheck.simulation.engine.InputBuilder.needs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realitycheck.simulation.engine.SimulationInput.RouteStep;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Routes (spec 11, task 6): batches follow their own path through the workflow. */
class RouteTest {

    /** Three stages, each halving stones, with one worker each. */
    private static InputBuilder threeStages() {
        InputBuilder factory = InputBuilder.alwaysOpen();
        for (String code : new String[] {"A", "B", "C"}) {
            UUID workers = factory.employees(code + " workers", 1, "100.00");
            factory.stage(code, "10", 0, "0.5", "0.5", "0", needs(workers, 1));
        }
        return factory;
    }

    private static int jobs(SimulationResult result, String code) {
        return result.stages().stream().filter(s -> s.code().equals(code)).mapToInt(StageResult::jobsCompleted).sum();
    }

    @Test
    void aBatchOnARouteVisitsOnlyItsStagesAndFinishesAtTheLastOne() {
        InputBuilder factory = threeStages().route("SKIP_B", 1, 100, 3, 100);
        factory.onRoute(factory.job("ORD-1", "10"), "SKIP_B");

        SimulationResult result = factory.run();

        assertThat(result.terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(jobs(result, "A")).isEqualTo(1);
        assertThat(jobs(result, "B")).isZero();
        assertThat(jobs(result, "C")).isEqualTo(1);
        // Two halvings, not three: 10 → 5 → 2.5.
        assertThat(result.summary().outputStones()).isEqualByComparingTo("2.5");
        assertThat(result.orders()).singleElement().satisfies(o -> assertThat(o.completionTime()).isNotNull());
    }

    @Test
    void outputIsWhatEachBatchProducesAtTheEndOfItsOwnPath() {
        InputBuilder factory = threeStages().route("SHORT", 1, 100, 2, 100);
        factory.onRoute(factory.job("ORD-1", "10"), "SHORT");
        factory.job("ORD-2", "8"); // no route: all three stages

        SimulationResult result = factory.run();

        // 10 → 5 → 2.5 ending at B, plus 8 → 4 → 2 → 1 ending at C.
        assertThat(result.summary().outputStones()).isEqualByComparingTo("3.5");
        assertThat(jobs(result, "C")).isEqualTo(1);
    }

    @Test
    void aRouteOverEveryStageAtFullShareGivesExactlyTheResultOfNoRoute() {
        InputBuilder plain = threeStages();
        InputBuilder routed = threeStages().route("ALL", 1, 100, 2, 100, 3, 100);
        for (InputBuilder factory : new InputBuilder[] {plain, routed}) {
            factory.job("ORD-1", "10");
            factory.job("ORD-2", "7");
        }
        routed.build().batches().forEach(b -> routed.onRoute(b.id(), "ALL"));

        assertThat(routed.run()).isEqualTo(plain.run());
    }

    @Test
    void anOptionalStepIsVisitedByAboutItsShareOfBatchesAndAlwaysByTheSameOnes() {
        RouteStep half = new RouteStep(2, 50);
        long visiting = IntStream.range(0, 2_000).filter(i -> SimulationContext.visits(new UUID(4, i), half)).count();
        assertThat(visiting).isBetween(900L, 1_100L);
        assertThat(SimulationContext.visits(new UUID(4, 7), new RouteStep(2, 100))).isTrue();

        InputBuilder factory = threeStages().route("MAYBE_B", 1, 100, 2, 50, 3, 100);
        for (int i = 0; i < 40; i++) {
            factory.onRoute(factory.job("ORD-" + i, "4"), "MAYBE_B");
        }
        SimulationResult first = factory.run();

        assertThat(jobs(first, "A")).isEqualTo(40);
        assertThat(jobs(first, "B")).isBetween(10, 30);
        assertThat(jobs(first, "C")).isEqualTo(40);
        assertThat(factory.run()).isEqualTo(first);
    }

    @Test
    void aBatchAlreadyAtAnOptionalStageStaysOnIt() {
        InputBuilder factory = threeStages().route("RARELY_B", 1, 100, 2, 1, 3, 100);
        factory.onRoute(factory.queued(factory.order("ORD-1"), 2, "10"), "RARELY_B");

        SimulationResult result = factory.run();

        assertThat(jobs(result, "B")).isEqualTo(1);
        assertThat(jobs(result, "C")).isEqualTo(1);
        assertThat(result.summary().outputStones()).isEqualByComparingTo("2.5");
    }

    @Test
    void invalidRoutesAreRefusedWithReasons() {
        InputBuilder factory = threeStages()
                .route("BAD", 1, 100, 1, 100, 9, 100, 2, 0)
                .route("OK", 1, 100, 3, 100);
        factory.onRoute(factory.job("ORD-1", "10"), "MISSING");
        factory.onRoute(factory.queued(factory.order("ORD-2"), 2, "5"), "OK");

        assertThatThrownBy(factory::run)
                .isInstanceOf(InvalidSimulationInputException.class)
                .satisfies(ex -> assertThat(((InvalidSimulationInputException) ex).problems())
                        .extracting(InvalidSimulationInputException.Problem::message)
                        .contains("Route BAD visits stage 1 more than once",
                                "Route BAD names stage 9, which is not in the workflow",
                                "Route BAD gives stage 2 a visit share outside 1 to 100 percent")
                        .anyMatch(m -> m.endsWith("follows route MISSING, which is not in the input"))
                        .anyMatch(m -> m.endsWith("is at stage 2, which route OK does not visit")));
    }
}
