package com.realitycheck.scenario.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.api.ComparisonDtos.MetricDelta;
import com.realitycheck.scenario.comparison.DeltaCalculator.Direction;
import com.realitycheck.scenario.comparison.RunResultsPort.OrderView;
import com.realitycheck.scenario.comparison.RunResultsPort.PoolView;
import com.realitycheck.scenario.comparison.RunResultsPort.RunView;
import com.realitycheck.scenario.comparison.RunResultsPort.StageView;
import com.realitycheck.scenario.comparison.RunResultsPort.SummaryView;
import com.realitycheck.scenario.service.ScenarioReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Comparability checks, warnings and the joins of spec 08, against hand-built runs. */
class ComparisonServiceTest {

    private static final UUID FACTORY = UUID.randomUUID();
    private static final UUID CUTTING = UUID.randomUUID();
    private static final UUID POLISHING = UUID.randomUUID();
    private static final UUID INSPECTION = UUID.randomUUID();
    private static final UUID WHEELS = UUID.randomUUID();
    private static final UUID EXTRA_POOL = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant FORK = Instant.parse("2026-01-12T05:30:00Z");

    private ComparisonService service;

    @BeforeEach
    void setUp() {
        ScenarioReader reader = mock(ScenarioReader.class);
        when(reader.names(any())).thenReturn(Map.of());
        service = new ComparisonService(mock(RunResultsPort.class), reader, mock(ActualOutcomeService.class));
    }

    @Test
    void aRunComparedWithItselfIsUnchangedEverywhere() {
        RunView run = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView same = copy(run, UUID.randomUUID());

        ComparisonResponse response = service.compare(run, List.of(same), false);

        assertThat(response.sameStartingState()).isTrue();
        assertThat(response.warnings()).isEmpty();
        var alternative = response.alternatives().get(0);
        assertThat(alternative.metrics()).hasSize(MetricCatalog.METRICS.size());
        for (MetricDelta delta : alternative.metrics()) {
            if (delta.absolute() != null) {
                assertThat(new BigDecimal(delta.absolute())).as(delta.key()).isEqualByComparingTo("0");
            }
            if (delta.betterWhen() != null && delta.absolute() != null) {
                assertThat(delta.direction()).as(delta.key()).isEqualTo(Direction.UNCHANGED);
            }
        }
        assertThat(alternative.bottleneck().change()).isEqualTo("SAME");
    }

    @Test
    void metricsCarryUnitsDirectionAndNoDirectionForUtilization() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView faster = withElapsed(copy(baseline, UUID.randomUUID()), 90);

        var metrics = service.compare(baseline, List.of(faster), false).alternatives().get(0).metrics();

        MetricDelta elapsed = metric(metrics, "elapsedSeconds");
        assertThat(elapsed.unit()).isEqualTo("seconds");
        assertThat(elapsed.absolute()).isEqualTo("-10");
        assertThat(elapsed.percent()).isEqualTo("-10.00");
        assertThat(elapsed.direction()).isEqualTo(Direction.IMPROVED);
        assertThat(metric(metrics, "machineUtilization").betterWhen()).isNull();
        assertThat(metric(metrics, "machineUtilization").direction()).isNull();
        assertThat(metric(metrics, "cost.total").betterWhen()).isEqualTo(DeltaCalculator.BetterWhen.LOWER);
    }

    @Test
    void aDifferentStartingStateNeedsConsentAndThenWarns() {
        RunView baseline = run("COMPLETED", "h1", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView other = run("COMPLETED", "h2", "1.0.0", "COMPLETED", POLISHING, "100");

        assertThatThrownBy(() -> service.compare(baseline, List.of(other), false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((ApiException) ex).properties()).containsEntry("code", "DIFFERENT_STARTING_STATE");
                });
        ComparisonResponse allowed = service.compare(baseline, List.of(other), true);
        assertThat(allowed.sameStartingState()).isFalse();
        assertThat(allowed.warnings()).extracting("code").containsExactly("DIFFERENT_STARTING_STATE");
    }

    @Test
    void differentAlgorithmVersionsAndIncompleteRunsAreWarnedAbout() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView newer = run("COMPLETED", "h", "1.1.0", "HORIZON_REACHED", POLISHING, "100");

        ComparisonResponse response = service.compare(baseline, List.of(newer), false);

        assertThat(response.warnings()).extracting("code")
                .containsExactly("DIFFERENT_ALGORITHM_VERSION", "INCOMPLETE_SIMULATION");
    }

    @Test
    void aRunThatIsNotCompletedCannotBeCompared() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView running = run("RUNNING", "h", "1.0.0", null, null, null);

        assertThatThrownBy(() -> service.compare(baseline, List.of(running), false))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("RUNNING");
    }

    @Test
    void bottleneckChangeTable() {
        assertThat(change(POLISHING, POLISHING)).isEqualTo("SAME");
        assertThat(change(POLISHING, INSPECTION)).isEqualTo("MOVED");
        assertThat(change(POLISHING, null)).isEqualTo("RESOLVED");
        assertThat(change(null, POLISHING)).isEqualTo("EMERGED");
        assertThat(change(null, null)).isEqualTo("NONE");
    }

    @Test
    void aMovedBottleneckCarriesBothStagesScoresInBothRuns() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView alternative = run("COMPLETED", "h", "1.0.0", "COMPLETED", INSPECTION, "100");

        var change = ComparisonService.bottleneck(baseline, alternative);

        assertThat(change.baselineStage().code()).isEqualTo("POLISHING");
        assertThat(change.baselineStage().baselineScore()).isEqualTo("0.800000");
        assertThat(change.alternativeStage().code()).isEqualTo("QUALITY_INSPECTION");
        assertThat(change.alternativeStage().baselineScore()).isEqualTo("0.300000");
        assertThat(change.alternativeStage().alternativeScore()).isEqualTo("0.700000");
        assertThat(change.baselineStage().alternativeScore()).isEqualTo("0.200000");
    }

    @Test
    void poolsOnOneSideOnlyAreKeptWithTheMissingSideNull() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView alternative = withPools(copy(baseline, UUID.randomUUID()), List.of(
                pool(WHEELS, 8), pool(EXTRA_POOL, 2)));

        var resources = ComparisonService.resources(baseline, alternative);

        assertThat(resources).hasSize(2);
        assertThat(resources.get(0).capacity().baseline()).isEqualTo("6");
        assertThat(resources.get(0).capacity().alternative()).isEqualTo("8");
        assertThat(resources.get(0).capacity().percent()).isEqualTo("33.33");
        assertThat(resources.get(1).capacity().baseline()).isNull();
        assertThat(resources.get(1).capacity().absolute()).isNull();
    }

    @Test
    void anOrderUnfinishedInEitherRunHasNoDifference() {
        RunView baseline = run("COMPLETED", "h", "1.0.0", "COMPLETED", POLISHING, "100");
        RunView unfinished = withOrders(copy(baseline, UUID.randomUUID()),
                List.of(new OrderView(ORDER, "ORD-1", FORK.plusSeconds(86_400), null, "UNFINISHED")));

        var orders = ComparisonService.orders(baseline, unfinished);

        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.differenceSeconds()).isNull();
            assertThat(o.alternativeCompletionAt()).isNull();
            assertThat(o.alternativeOutcome()).isEqualTo("UNFINISHED");
        });
    }

    private String change(UUID baselineStage, UUID alternativeStage) {
        return ComparisonService.bottleneck(run("COMPLETED", "h", "1.0.0", "COMPLETED", baselineStage, "100"),
                run("COMPLETED", "h", "1.0.0", "COMPLETED", alternativeStage, "100")).change();
    }

    private static MetricDelta metric(List<MetricDelta> metrics, String key) {
        return metrics.stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    private static RunView run(String status, String hash, String version, String termination, UUID bottleneck,
            String elapsed) {
        boolean completed = "COMPLETED".equals(status);
        SummaryView summary = !completed ? null : summary(Long.parseLong(elapsed));
        List<StageView> stages = !completed ? List.of() : List.of(
                stage(CUTTING, "CUTTING", 1, "0.100000"),
                stage(POLISHING, "POLISHING", 2, bottleneck == POLISHING ? "0.800000" : "0.200000"),
                stage(INSPECTION, "QUALITY_INSPECTION", 3, bottleneck == INSPECTION ? "0.700000" : "0.300000"));
        return new RunView(UUID.randomUUID(), UUID.randomUUID(), FACTORY, status, FORK, hash, version, termination,
                "INR", summary, bottleneck, stages, completed ? List.of(pool(WHEELS, 6)) : List.of(),
                completed ? List.of(new OrderView(ORDER, "ORD-1", FORK.plusSeconds(86_400), FORK.plusSeconds(3600),
                        "ON_TIME")) : List.of(),
                List.of());
    }

    private static SummaryView summary(long elapsed) {
        return new SummaryView(elapsed, 50, 10, new BigDecimal("4.0000"), new BigDecimal("100.0000"),
                new BigDecimal("50.0000"), new BigDecimal("1000.00"), new BigDecimal("10.0000"),
                new BigDecimal("600.00"), new BigDecimal("300.00"), new BigDecimal("100.00"),
                new BigDecimal("0.500000"), new BigDecimal("0.400000"), 1, 0, new BigDecimal("1.000000"));
    }

    private static StageView stage(UUID id, String code, int sequence, String score) {
        return new StageView(id, code, code, sequence, new BigDecimal("60.0000"), new BigDecimal("1.0000"), 2,
                new BigDecimal("4.0000"), new BigDecimal("0.500000"), new BigDecimal(score));
    }

    private static PoolView pool(UUID id, int capacity) {
        return new PoolView(id, "Pool " + capacity, "MACHINE", capacity, new BigDecimal("0.500000"), 100, 100,
                new BigDecimal("10.00"));
    }

    private static RunView copy(RunView r, UUID runId) {
        return new RunView(runId, UUID.randomUUID(), r.factoryId(), r.status(), r.forkAt(), r.baseStateHash(),
                r.algorithmVersion(), r.terminationReason(), r.currencyCode(), r.summary(),
                r.primaryBottleneckStageId(), r.stages(), r.pools(), r.orders(), r.appliedChanges());
    }

    private static RunView withElapsed(RunView r, long elapsed) {
        return new RunView(r.runId(), r.scenarioId(), r.factoryId(), r.status(), r.forkAt(), r.baseStateHash(),
                r.algorithmVersion(), r.terminationReason(), r.currencyCode(), summary(elapsed),
                r.primaryBottleneckStageId(), r.stages(), r.pools(), r.orders(), r.appliedChanges());
    }

    private static RunView withPools(RunView r, List<PoolView> pools) {
        return new RunView(r.runId(), r.scenarioId(), r.factoryId(), r.status(), r.forkAt(), r.baseStateHash(),
                r.algorithmVersion(), r.terminationReason(), r.currencyCode(), r.summary(),
                r.primaryBottleneckStageId(), r.stages(), pools, r.orders(), r.appliedChanges());
    }

    private static RunView withOrders(RunView r, List<OrderView> orders) {
        return new RunView(r.runId(), r.scenarioId(), r.factoryId(), r.status(), r.forkAt(), r.baseStateHash(),
                r.algorithmVersion(), r.terminationReason(), r.currencyCode(), r.summary(),
                r.primaryBottleneckStageId(), r.stages(), r.pools(), orders, r.appliedChanges());
    }
}
