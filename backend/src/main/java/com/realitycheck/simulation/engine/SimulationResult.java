package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.ResourceKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything a run produces. Metric definitions are in .kiro/steering/simulation-rules.md,
 * section 11. Scales: quantities 4, ratios 6, means 4, money 2.
 *
 * @param endTime the last stage completion (T1), or the fork time if nothing completed
 * @param trace   what happened, in the order it happened
 */
public record SimulationResult(
        String algorithmVersion,
        long forkTime,
        long endTime,
        TerminationReason terminationReason,
        long eventsProcessed,
        SummaryMetrics summary,
        List<StageResult> stages,
        List<PoolResult> pools,
        List<OrderResult> orders,
        BottleneckResult bottleneck,
        List<UnfinishedJob> unfinished,
        List<TraceEvent> trace) {

    public enum TerminationReason {
        /** Every batch finished the last stage. */
        COMPLETED,
        /** Work remained when the simulated-time limit was reached. */
        HORIZON_REACHED,
        /** Work remained when the event limit was reached. */
        EVENT_LIMIT_REACHED
    }

    /**
     * @param elapsedSeconds          T1 - T0: calendar time, nights and closed days included
     * @param workingSecondsInWindow  factory working time in [T0, T1]
     * @param processingSeconds       working seconds of setup and processing, summed over jobs
     * @param waitingWorkingSeconds   working seconds jobs spent queued
     * @param waitingCalendarSeconds  calendar seconds jobs spent queued
     * @param outputStones            stones that finished the last stage, unrounded
     * @param outputStonesRounded     the same, rounded half-even to whole stones for display
     * @param stoneYield              output over input; null when input is zero
     * @param throughputStonesPerHour output per working hour; null when there was no working time
     * @param machineUtilization      capacity-weighted over machine pools; null when none was available
     * @param onTimeDeliveryRate      on-time orders over orders with a due date; null when there are none
     */
    public record SummaryMetrics(
            long elapsedSeconds,
            long workingSecondsInWindow,
            long processingSeconds,
            long waitingWorkingSeconds,
            long waitingCalendarSeconds,
            BigDecimal inputStones,
            BigDecimal outputStones,
            BigDecimal outputStonesRounded,
            BigDecimal inputCarats,
            BigDecimal outputCarats,
            BigDecimal stoneYield,
            BigDecimal caratYield,
            BigDecimal throughputStonesPerHour,
            BigDecimal machineUtilization,
            BigDecimal employeeUtilization,
            CostBreakdown cost,
            int ordersTotal,
            int ordersCompleted,
            int ordersDelayed,
            int ordersWithDueDate,
            BigDecimal onTimeDeliveryRate) {
    }

    /**
     * @param employee       scheduled capacity over working time in [T0, T1]: people are paid when idle
     * @param machine        busy unit-hours only
     * @param fixed          each stage's fixed cost per completed job
     * @param perOutputStone total over output stones; null when nothing was produced
     */
    public record CostBreakdown(BigDecimal employee, BigDecimal machine, BigDecimal fixed, BigDecimal total,
            BigDecimal perOutputStone) {
    }

    /**
     * @param meanWaitWorkingSeconds mean working wait of jobs started here; zero when none
     * @param meanStageSeconds       mean working seconds from queue entry to completion
     * @param meanQueueLength        time-weighted over working time in [T0, T1]
     */
    public record StageResult(
            UUID stageId,
            String code,
            int sequence,
            int jobsCompleted,
            BigDecimal inputStones,
            BigDecimal outputStones,
            BigDecimal lossStones,
            BigDecimal inputCarats,
            BigDecimal outputCarats,
            BigDecimal lossCarats,
            long processingSeconds,
            long waitingWorkingSeconds,
            long waitingCalendarSeconds,
            BigDecimal meanWaitWorkingSeconds,
            BigDecimal meanStageSeconds,
            long maxStageSeconds,
            BigDecimal meanQueueLength,
            int maxQueueLength,
            BigDecimal throughputStonesPerHour,
            BigDecimal fixedCost,
            BottleneckSignals bottleneck) {
    }

    /**
     * @param busyUnitSeconds      unit-seconds spent processing
     * @param availableUnitSeconds capacity integrated over working time in [T0, T1]
     * @param utilization          busy over available, capped at 1; null when nothing was available
     * @param utilizationNullReason {@code NO_AVAILABLE_TIME} when utilization is null
     * @param overcommitted        the pool was asked for more than it had, because a job already
     *                             running kept its units through a capacity reduction
     */
    public record PoolResult(
            UUID poolId,
            ResourceKind kind,
            String name,
            long busyUnitSeconds,
            long availableUnitSeconds,
            long idleUnitSeconds,
            BigDecimal utilization,
            String utilizationNullReason,
            boolean overcommitted,
            BigDecimal cost) {

        public static final String NO_AVAILABLE_TIME = "NO_AVAILABLE_TIME";
    }

    public enum OrderOutcome {
        ON_TIME,
        LATE,
        UNFINISHED
    }

    /**
     * @param completionTime when its last batch finished; null if unfinished
     * @param delayed        late, or unfinished with a due date before the end of the run
     */
    public record OrderResult(UUID orderId, String orderNumber, Long completionTime, Long dueTime,
            OrderOutcome outcome, boolean delayed) {
    }

    /**
     * The evidence behind a stage's bottleneck score. Each signal is in [0, 1].
     *
     * @param limitingPoolId    the required pool with the highest utilization; null if the stage needs none
     * @param utilizationSignal that pool's utilization over the whole run. Every run has a start-up
     *                          and a tail in which a stage is short of input, so this understates
     *                          how hard a constrained stage was pressed; see {@code saturationSignal}
     * @param waitSignal        mean working wait here, relative to the largest across stages
     * @param queueSignal       mean queue length here, relative to the largest across stages
     * @param saturationSignal  of the working time in which the stage had work (queued or running),
     *                          the share spent with work waiting and no free slot
     * @param blockedShare      the same blocked time as a share of all working time in the run
     * @param score             weighted sum of the four signals
     */
    public record BottleneckSignals(
            UUID limitingPoolId,
            BigDecimal limitingPoolUtilization,
            BigDecimal utilizationSignal,
            BigDecimal waitSignal,
            BigDecimal queueSignal,
            BigDecimal saturationSignal,
            BigDecimal blockedShare,
            BigDecimal score) {
    }

    /**
     * @param primaryStageId null when no stage qualifies
     * @param reasonCode     one of the constants below
     */
    public record BottleneckResult(UUID primaryStageId, String reasonCode, BottleneckConfig configUsed) {

        /** Highest score, above the threshold, and work waited behind full slots for much of the stage's active time. */
        public static final String CONSTRAINED_POOL_WITH_QUEUE = "CONSTRAINED_POOL_WITH_QUEUE";
        /** Work waited longest here, but only briefly relative to how long the stage was active. */
        public static final String NO_SUSTAINED_QUEUE = "NO_SUSTAINED_QUEUE";
        public static final String NO_CLEAR_BOTTLENECK = "NO_CLEAR_BOTTLENECK";
        public static final String NO_WORK = "NO_WORK";
    }

    /** A batch that had not finished when the run ended. {@code state} is QUEUED, RUNNING or NOT_ARRIVED. */
    public record UnfinishedJob(UUID batchId, UUID orderId, int stageSequence, String state) {
    }

    public enum TraceType {
        JOB_QUEUED,
        JOB_STARTED,
        JOB_COMPLETED,
        CAPACITY_CHANGED,
        ORDER_COMPLETED
    }

    /**
     * @param stones   for JOB_COMPLETED the output; for JOB_QUEUED and JOB_STARTED the input
     * @param capacity the pool's new capacity, for CAPACITY_CHANGED
     */
    public record TraceEvent(long seq, long time, TraceType type, UUID batchId, UUID orderId, UUID stageId,
            UUID poolId, BigDecimal stones, BigDecimal carats, Integer capacity) {
    }
}
