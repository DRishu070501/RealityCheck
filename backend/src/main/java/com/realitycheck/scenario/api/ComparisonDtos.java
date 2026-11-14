package com.realitycheck.scenario.api;

import com.realitycheck.scenario.comparison.DeltaCalculator.BetterWhen;
import com.realitycheck.scenario.comparison.DeltaCalculator.Direction;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shapes of the comparison API. Every value is read from stored simulation results; the
 * comparison only subtracts. Numbers are strings, as elsewhere.
 */
public final class ComparisonDtos {

    public static final String SIMULATED = "SIMULATED";

    private ComparisonDtos() {
    }

    /**
     * @param allowDifferentBaseline compare even if a run started from a different factory state
     * @param includeActual          not available yet (planned); must be false or absent
     */
    public record CompareRequest(@NotNull UUID baselineRunId,
            @NotEmpty @Size(max = 5) List<@NotNull UUID> alternativeRunIds, Boolean allowDifferentBaseline,
            Boolean includeActual) {
    }

    /**
     * @param sameStartingState every alternative has the baseline's fork and starting-state hash
     * @param actual            always null for now: the recorded-outcome block is not built yet
     */
    public record ComparisonResponse(String source, String currencyCode, boolean sameStartingState, Instant forkAt,
            List<ComparisonWarning> warnings, ComparedRun baseline, List<AlternativeComparison> alternatives,
            Object actual) {
    }

    /** @param code {@code DIFFERENT_STARTING_STATE}, {@code DIFFERENT_ALGORITHM_VERSION} or {@code INCOMPLETE_SIMULATION} */
    public record ComparisonWarning(String code, UUID runId, String message) {
    }

    public record ComparedRun(UUID runId, UUID scenarioId, String scenarioName, String algorithmVersion,
            String terminationReason, List<AppliedChangeView> appliedChanges) {
    }

    public record AppliedChangeView(int ordinal, String changeType, String summary) {
    }

    public record AlternativeComparison(ComparedRun run, List<MetricDelta> metrics, BottleneckChange bottleneck,
            List<StageComparison> stages, List<ResourceComparison> resources, List<OrderComparison> orders) {
    }

    /**
     * @param absolute  alternative minus baseline; null when either is null
     * @param percent   relative to the baseline, scale 2; null when the baseline is zero or null
     * @param direction null when {@code betterWhen} is null
     */
    public record MetricDelta(String key, String label, String unit, String baseline, String alternative,
            String absolute, String percent, BetterWhen betterWhen, Direction direction) {
    }

    /** A value in both runs and the difference; a side is null when absent from that run. */
    public record Delta(String baseline, String alternative, String absolute, String percent) {
    }

    /** @param change {@code SAME}, {@code MOVED}, {@code RESOLVED}, {@code EMERGED} or {@code NONE} */
    public record BottleneckChange(String change, BottleneckStage baselineStage, BottleneckStage alternativeStage) {
    }

    /** A stage's bottleneck score in each run. */
    public record BottleneckStage(UUID stageId, String code, String name, String baselineScore,
            String alternativeScore) {
    }

    public record StageComparison(UUID stageId, String code, String name, Integer sequence,
            Delta meanWaitWorkingSeconds, Delta meanQueueLength, Delta maxQueueLength,
            Delta throughputStonesPerHour, Delta limitingUtilization, Delta bottleneckScore) {
    }

    public record ResourceComparison(UUID resourceId, String name, String kind, Delta capacity, Delta utilization,
            Delta busyUnitSeconds, Delta idleUnitSeconds, Delta cost) {
    }

    /** @param differenceSeconds alternative minus baseline completion; null if unfinished in either */
    public record OrderComparison(UUID orderId, String orderNumber, Instant dueAt, Instant baselineCompletionAt,
            Instant alternativeCompletionAt, Long differenceSeconds, String baselineOutcome,
            String alternativeOutcome) {
    }

    /**
     * @param baselineMissing             no baseline run with the same starting state exists
     * @param suggestedBaselineScenarioId a baseline scenario at the same fork that could be run
     */
    public record ScenarioComparisonsResponse(boolean baselineMissing, UUID suggestedBaselineScenarioId,
            List<ComparisonResponse> items) {
    }
}
