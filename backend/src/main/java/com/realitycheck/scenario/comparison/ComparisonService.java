package com.realitycheck.scenario.comparison;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.scenario.api.ComparisonDtos;
import com.realitycheck.scenario.api.ComparisonDtos.AlternativeComparison;
import com.realitycheck.scenario.api.ComparisonDtos.AppliedChangeView;
import com.realitycheck.scenario.api.ComparisonDtos.BottleneckChange;
import com.realitycheck.scenario.api.ComparisonDtos.BottleneckStage;
import com.realitycheck.scenario.api.ComparisonDtos.ComparedRun;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonWarning;
import com.realitycheck.scenario.api.ComparisonDtos.CompareRequest;
import com.realitycheck.scenario.api.ComparisonDtos.Delta;
import com.realitycheck.scenario.api.ComparisonDtos.MetricDelta;
import com.realitycheck.scenario.api.ComparisonDtos.OrderComparison;
import com.realitycheck.scenario.api.ComparisonDtos.ResourceComparison;
import com.realitycheck.scenario.api.ComparisonDtos.ScenarioComparisonsResponse;
import com.realitycheck.scenario.api.ComparisonDtos.StageComparison;
import com.realitycheck.scenario.comparison.MetricCatalog.Metric;
import com.realitycheck.scenario.comparison.RunResultsPort.OrderView;
import com.realitycheck.scenario.comparison.RunResultsPort.PoolView;
import com.realitycheck.scenario.comparison.RunResultsPort.RunView;
import com.realitycheck.scenario.comparison.RunResultsPort.StageView;
import com.realitycheck.scenario.service.ScenarioReader;
import com.realitycheck.scenario.service.ScenarioReader.ScenarioView;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts a baseline run next to alternatives and reports differences. Every value comes from stored
 * results; this class only checks comparability and subtracts. Comparisons are not stored: runs
 * are immutable, so the same runs always give the same comparison.
 */
@Service
@Transactional(readOnly = true)
public class ComparisonService {

    public static final String DIFFERENT_STARTING_STATE = "DIFFERENT_STARTING_STATE";
    public static final String DIFFERENT_ALGORITHM_VERSION = "DIFFERENT_ALGORITHM_VERSION";
    public static final String INCOMPLETE_SIMULATION = "INCOMPLETE_SIMULATION";
    private static final String COMPLETED = "COMPLETED";

    private final RunResultsPort results;
    private final ScenarioReader scenarioReader;
    private final ActualOutcomeService actualOutcome;

    public ComparisonService(RunResultsPort results, ScenarioReader scenarioReader,
            ActualOutcomeService actualOutcome) {
        this.results = results;
        this.scenarioReader = scenarioReader;
        this.actualOutcome = actualOutcome;
    }

    public ComparisonResponse compare(CompareRequest request) {
        List<UUID> alternativeIds = request.alternativeRunIds();
        if (new HashSet<>(alternativeIds).size() != alternativeIds.size()) {
            throw ApiException.badRequest("alternativeRunIds must not repeat a run.");
        }
        if (alternativeIds.contains(request.baselineRunId())) {
            throw ApiException.badRequest("The baseline run cannot also be an alternative.");
        }
        RunView baseline = load(request.baselineRunId());
        List<RunView> alternatives = alternativeIds.stream().map(this::load).toList();
        ComparisonResponse response = compare(baseline, alternatives,
                Boolean.TRUE.equals(request.allowDifferentBaseline()));
        if (!Boolean.TRUE.equals(request.includeActual())) {
            return response;
        }
        // Shown beside the simulated figures only: history stops at asOf, a simulation runs to completion.
        return new ComparisonResponse(response.source(), response.currencyCode(), response.sameStartingState(),
                response.forkAt(), response.warnings(), response.baseline(), response.alternatives(),
                actualOutcome.after(baseline).orElse(null));
    }

    /** The scenario's latest completed run against the latest matching run of each baseline scenario. */
    public ScenarioComparisonsResponse forScenario(UUID scenarioId) {
        ScenarioView scenario = scenarioReader.find(scenarioId)
                .orElseThrow(() -> ApiException.notFound("Scenario", scenarioId));
        if (scenario.baseline()) {
            return new ScenarioComparisonsResponse(false, null, List.of());
        }
        Optional<RunView> latest = results.latestCompleted(scenarioId);
        if (latest.isEmpty()) {
            return new ScenarioComparisonsResponse(false, null, List.of());
        }
        RunView alternative = latest.get();
        List<ComparisonResponse> items = new ArrayList<>();
        UUID suggested = null;
        for (ScenarioView candidate : scenarioReader.forFactory(scenario.factoryId())) {
            if (!candidate.baseline() || candidate.id().equals(scenarioId)) {
                continue;
            }
            Optional<RunView> baselineRun = results.latestCompleted(candidate.id(), alternative.baseStateHash());
            if (baselineRun.isPresent()) {
                items.add(compare(baselineRun.get(), List.of(alternative), false));
            } else if (suggested == null && candidate.forkAt().equals(scenario.forkAt())
                    && candidate.includeFutureOrders() == scenario.includeFutureOrders()) {
                suggested = candidate.id();
            }
        }
        return items.isEmpty() ? new ScenarioComparisonsResponse(true, suggested, List.of())
                : new ScenarioComparisonsResponse(false, null, items);
    }

    ComparisonResponse compare(RunView baseline, List<RunView> alternatives, boolean allowDifferentBaseline) {
        List<RunView> all = new ArrayList<>();
        all.add(baseline);
        all.addAll(alternatives);
        for (RunView run : all) {
            if (!run.factoryId().equals(baseline.factoryId())) {
                throw ApiException.badRequest("Run " + run.runId() + " belongs to a different factory.");
            }
        }
        for (RunView run : all) {
            if (!COMPLETED.equals(run.status())) {
                throw ApiException.conflict("Run " + run.runId() + " is " + run.status()
                        + "; only COMPLETED runs can be compared.")
                        .withProperty("runId", run.runId()).withProperty("runStatus", run.status());
            }
        }
        List<ComparisonWarning> warnings = new ArrayList<>();
        List<UUID> differing = new ArrayList<>();
        for (RunView alternative : alternatives) {
            if (!sameStart(baseline, alternative)) {
                differing.add(alternative.runId());
                warnings.add(new ComparisonWarning(DIFFERENT_STARTING_STATE, alternative.runId(),
                        "This run started from a different factory state (fork time, recorded history or"
                                + " configuration), so differences are not caused by its changes alone."));
            }
            if (!alternative.algorithmVersion().equals(baseline.algorithmVersion())) {
                warnings.add(new ComparisonWarning(DIFFERENT_ALGORITHM_VERSION, alternative.runId(),
                        "Computed by simulation algorithm " + alternative.algorithmVersion() + ", the baseline by "
                                + baseline.algorithmVersion() + "."));
            }
        }
        if (!differing.isEmpty() && !allowDifferentBaseline) {
            throw ApiException.unprocessable("These runs did not start from the same factory state, so their"
                    + " differences would not be caused by the scenario changes alone. Pass"
                    + " allowDifferentBaseline=true to compare them anyway.")
                    .withProperty("code", DIFFERENT_STARTING_STATE).withProperty("runIds", differing);
        }
        for (RunView run : all) {
            if (!COMPLETED.equals(run.terminationReason())) {
                warnings.add(new ComparisonWarning(INCOMPLETE_SIMULATION, run.runId(),
                        "The simulation stopped at " + run.terminationReason()
                                + " with work unfinished; its totals cover only what was done."));
            }
        }

        Map<UUID, String> names = scenarioReader.names(all.stream().map(RunView::scenarioId).distinct().toList());
        List<AlternativeComparison> compared = alternatives.stream()
                .map(alternative -> new AlternativeComparison(describe(alternative, names),
                        metrics(baseline, alternative), bottleneck(baseline, alternative),
                        stages(baseline, alternative), resources(baseline, alternative), orders(baseline, alternative)))
                .toList();
        return new ComparisonResponse(ComparisonDtos.SIMULATED, baseline.currencyCode(), differing.isEmpty(),
                baseline.forkAt(), warnings, describe(baseline, names), compared, null);
    }

    static boolean sameStart(RunView a, RunView b) {
        return a.forkAt().equals(b.forkAt()) && a.baseStateHash().equals(b.baseStateHash());
    }

    static List<MetricDelta> metrics(RunView baseline, RunView alternative) {
        List<MetricDelta> deltas = new ArrayList<>();
        for (Metric metric : MetricCatalog.METRICS) {
            BigDecimal b = metric.extractor().apply(baseline.summary());
            BigDecimal a = metric.extractor().apply(alternative.summary());
            BigDecimal absolute = DeltaCalculator.absolute(b, a);
            deltas.add(new MetricDelta(metric.key(), metric.label(), metric.unit(), text(b), text(a), text(absolute),
                    text(DeltaCalculator.percent(b, a)), metric.betterWhen(),
                    DeltaCalculator.direction(metric.betterWhen(), absolute)));
        }
        return deltas;
    }

    static BottleneckChange bottleneck(RunView baseline, RunView alternative) {
        UUID b = baseline.primaryBottleneckStageId();
        UUID a = alternative.primaryBottleneckStageId();
        String change = b == null && a == null ? "NONE"
                : Objects.equals(b, a) ? "SAME"
                : a == null ? "RESOLVED"
                : b == null ? "EMERGED"
                : "MOVED";
        return new BottleneckChange(change, stageScores(b, baseline, alternative), stageScores(a, baseline, alternative));
    }

    private static BottleneckStage stageScores(UUID stageId, RunView baseline, RunView alternative) {
        if (stageId == null) {
            return null;
        }
        StageView inBaseline = stage(baseline, stageId);
        StageView inAlternative = stage(alternative, stageId);
        StageView any = inBaseline != null ? inBaseline : inAlternative;
        return new BottleneckStage(stageId, any == null ? null : any.code(), any == null ? null : any.name(),
                inBaseline == null ? null : text(inBaseline.bottleneckScore()),
                inAlternative == null ? null : text(inAlternative.bottleneckScore()));
    }

    private static StageView stage(RunView run, UUID stageId) {
        return run.stages().stream().filter(s -> s.stageId().equals(stageId)).findFirst().orElse(null);
    }

    static List<StageComparison> stages(RunView baseline, RunView alternative) {
        Map<UUID, StageView> b = index(baseline.stages(), StageView::stageId);
        Map<UUID, StageView> a = index(alternative.stages(), StageView::stageId);
        List<StageComparison> result = new ArrayList<>();
        for (UUID id : union(b.keySet(), a.keySet())) {
            StageView x = b.get(id);
            StageView y = a.get(id);
            StageView any = x != null ? x : y;
            result.add(new StageComparison(id, any.code(), any.name(), any.sequence(),
                    delta(x, y, StageView::meanWaitWorkingSeconds), delta(x, y, StageView::meanQueueLength),
                    delta(x, y, s -> BigDecimal.valueOf(s.maxQueueLength())),
                    delta(x, y, StageView::throughputStonesPerHour), delta(x, y, StageView::limitingUtilization),
                    delta(x, y, StageView::bottleneckScore)));
        }
        result.sort((p, q) -> Integer.compare(p.sequence(), q.sequence()));
        return result;
    }

    static List<ResourceComparison> resources(RunView baseline, RunView alternative) {
        Map<UUID, PoolView> b = index(baseline.pools(), PoolView::resourceId);
        Map<UUID, PoolView> a = index(alternative.pools(), PoolView::resourceId);
        List<ResourceComparison> result = new ArrayList<>();
        for (UUID id : union(b.keySet(), a.keySet())) {
            PoolView x = b.get(id);
            PoolView y = a.get(id);
            PoolView any = x != null ? x : y;
            result.add(new ResourceComparison(id, any.name(), any.kind(),
                    delta(x, y, p -> BigDecimal.valueOf(p.capacity())), delta(x, y, PoolView::utilization),
                    delta(x, y, p -> BigDecimal.valueOf(p.busyUnitSeconds())),
                    delta(x, y, p -> BigDecimal.valueOf(p.idleUnitSeconds())), delta(x, y, PoolView::cost)));
        }
        return result;
    }

    static List<OrderComparison> orders(RunView baseline, RunView alternative) {
        Map<UUID, OrderView> a = index(alternative.orders(), OrderView::orderId);
        List<OrderComparison> result = new ArrayList<>();
        for (OrderView x : baseline.orders()) {
            OrderView y = a.get(x.orderId());
            if (y == null) {
                continue;
            }
            Long difference = x.completionAt() == null || y.completionAt() == null ? null
                    : Duration.between(x.completionAt(), y.completionAt()).toSeconds();
            result.add(new OrderComparison(x.orderId(), x.orderNumber(), x.dueAt(), x.completionAt(),
                    y.completionAt(), difference, x.outcome(), y.outcome()));
        }
        return result;
    }

    private static ComparedRun describe(RunView run, Map<UUID, String> names) {
        return new ComparedRun(run.runId(), run.scenarioId(), names.get(run.scenarioId()), run.algorithmVersion(),
                run.terminationReason(), run.appliedChanges().stream()
                        .map(c -> new AppliedChangeView(c.ordinal(), c.changeType(), c.summary()))
                        .toList());
    }

    private static <T> Delta delta(T baseline, T alternative, Function<T, BigDecimal> value) {
        BigDecimal b = baseline == null ? null : value.apply(baseline);
        BigDecimal a = alternative == null ? null : value.apply(alternative);
        return new Delta(text(b), text(a), text(DeltaCalculator.absolute(b, a)), text(DeltaCalculator.percent(b, a)));
    }

    private static <T> Map<UUID, T> index(List<T> items, Function<T, UUID> id) {
        Map<UUID, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(id.apply(item), item));
        return map;
    }

    private static List<UUID> union(Set<UUID> first, Set<UUID> second) {
        List<UUID> ids = new ArrayList<>(first);
        second.stream().filter(id -> !first.contains(id)).forEach(ids::add);
        return ids;
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private RunView load(UUID runId) {
        return results.load(runId).orElseThrow(() -> ApiException.notFound("Simulation run", runId));
    }
}
