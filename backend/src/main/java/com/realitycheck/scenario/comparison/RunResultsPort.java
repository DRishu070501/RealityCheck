package com.realitycheck.scenario.comparison;

import com.realitycheck.scenario.snapshot.Snapshot.AppliedChange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored simulation results as the comparison needs them. Declared here and implemented in
 * {@code simulation}, keeping the module dependency {@code simulation -> scenario}.
 */
public interface RunResultsPort {

    /** @return the run, or empty when it does not exist. Result fields are empty unless COMPLETED. */
    Optional<RunView> load(UUID runId);

    /** The newest COMPLETED run of a scenario with the given starting state. */
    Optional<RunView> latestCompleted(UUID scenarioId, String baseStateHash);

    /** The newest COMPLETED run of a scenario. */
    Optional<RunView> latestCompleted(UUID scenarioId);

    /**
     * @param appliedChanges from the run's stored snapshot, not the scenario's current changes
     * @param summary        null unless COMPLETED
     */
    record RunView(UUID runId, UUID scenarioId, UUID factoryId, String status, Instant forkAt, String baseStateHash,
            String algorithmVersion, String terminationReason, String currencyCode, SummaryView summary,
            UUID primaryBottleneckStageId, List<StageView> stages, List<PoolView> pools, List<OrderView> orders,
            List<AppliedChange> appliedChanges) {
    }

    /** The headline figures, exactly as stored. Durations in seconds; ratios as fractions. */
    record SummaryView(long elapsedSeconds, long processingSeconds, long waitingWorkingSeconds,
            BigDecimal throughputStonesPerHour, BigDecimal outputStones, BigDecimal outputCarats,
            BigDecimal costTotal, BigDecimal costPerOutputStone, BigDecimal costEmployee, BigDecimal costMachine,
            BigDecimal costFixed, BigDecimal machineUtilization, BigDecimal employeeUtilization, int ordersCompleted,
            int ordersDelayed, BigDecimal onTimeDeliveryRate) {
    }

    record StageView(UUID stageId, String code, String name, int sequence, BigDecimal meanWaitWorkingSeconds,
            BigDecimal meanQueueLength, int maxQueueLength, BigDecimal throughputStonesPerHour,
            BigDecimal limitingUtilization, BigDecimal bottleneckScore) {
    }

    record PoolView(UUID resourceId, String name, String kind, int capacity, BigDecimal utilization,
            long busyUnitSeconds, long idleUnitSeconds, BigDecimal cost) {
    }

    /** @param completionAt null when unfinished in the run */
    record OrderView(UUID orderId, String orderNumber, Instant dueAt, Instant completionAt, String outcome) {
    }
}
