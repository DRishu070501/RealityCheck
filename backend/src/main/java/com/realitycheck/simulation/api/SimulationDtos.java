package com.realitycheck.simulation.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes for simulation results. Every number here was computed by the deterministic
 * engine from recorded data and configuration; the top-level response carries
 * {@code source: "SIMULATED"}. These are estimates under the stated rules, not guarantees.
 *
 * <p>Durations are whole seconds with the unit in the field name. "Working" seconds count only
 * time the factory is open; "elapsed" and "calendar" seconds include nights and closed days.
 * Decimals are strings. Ratios are fractions between 0 and 1.
 */
public final class SimulationDtos {

    public static final String SIMULATED = "SIMULATED";

    private SimulationDtos() {
    }

    /**
     * @param forkAt            the instant the simulation started from
     * @param endsAt            when the last stage completed; equal to {@code forkAt} if nothing ran
     * @param terminationReason {@code COMPLETED}, or the limit that stopped the run with work left
     * @param unfinished        batches not finished when the run stopped; empty when completed
     */
    public record SimulationResultResponse(
            UUID factoryId,
            Instant forkAt,
            Instant endsAt,
            String algorithmVersion,
            String terminationReason,
            long eventsProcessed,
            Summary summary,
            List<StageResult> stages,
            List<PoolResult> pools,
            List<OrderResult> orders,
            Bottleneck bottleneck,
            List<Unfinished> unfinished,
            String source) {
    }

    /**
     * @param elapsedSeconds          from fork to last completion, in calendar time
     * @param workingSeconds          factory working time in that window
     * @param processingSeconds       working seconds of setup and processing, summed over jobs
     * @param waitingWorkingSeconds   working seconds jobs spent queued, summed over jobs
     * @param outputStones            stones finishing the last stage, unrounded expected value
     * @param outputStonesRounded     the same rounded to whole stones, for display
     * @param stoneYield              output over input; null when there was no input
     * @param throughputStonesPerHour output per working hour; null when there was no working time
     * @param machineUtilization      busy over available, capacity-weighted; null when nothing was available
     * @param onTimeDeliveryRate      on-time orders over orders with a due date; null when there are none
     * @param parkingSeconds          calendar seconds batches were held at parking stages, summed; null for
     *                                runs computed before algorithm 2.0.0
     * @param waitingStageSeconds     the same at waiting stages (such as waiting for dispatch)
     * @param jobWorkSeconds          the same at outside job workers
     * @param handlingSeconds         the same after processing at working stages (handling delay)
     * @param batchLeadMedianSeconds  median calendar seconds from a batch becoming active in the run to
     *                                finishing its path; null when none finished or before 2.0.0
     * @param batchManufacturingMedianSeconds the same with time parked left out
     */
    public record Summary(
            long elapsedSeconds,
            long workingSeconds,
            long processingSeconds,
            long waitingWorkingSeconds,
            long waitingCalendarSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputStonesRounded,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputCarats,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputCarats,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stoneYield,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal caratYield,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal throughputStonesPerHour,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal machineUtilization,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employeeUtilization,
            Cost cost,
            int ordersTotal,
            int ordersCompleted,
            int ordersDelayed,
            int ordersWithDueDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal onTimeDeliveryRate,
            Long parkingSeconds,
            Long waitingStageSeconds,
            Long jobWorkSeconds,
            Long handlingSeconds,
            Long batchLeadMedianSeconds,
            Long batchManufacturingMedianSeconds) {
    }

    /**
     * In the factory's currency.
     *
     * @param employee       scheduled employee capacity over the run's working time: idle time is paid
     * @param machine        machine unit-hours actually run
     * @param fixed          each stage's fixed cost per completed job
     * @param perOutputStone total over output stones; null when nothing was produced. Runs of
     *                       different length accrue cost over different periods, so compare this
     *                       as well as the total
     */
    public record Cost(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal employee,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal machine,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixed,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perOutputStone,
            String currencyCode) {
    }

    /**
     * @param meanWaitWorkingSeconds mean working wait of jobs started at the stage
     * @param meanStageSeconds       mean working seconds from joining the queue to completion
     * @param meanQueueLength        time-weighted over the run's working time
     */
    public record StageResult(
            UUID stageId,
            String code,
            String name,
            int sequence,
            int jobsCompleted,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lossStones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputCarats,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputCarats,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lossCarats,
            long processingSeconds,
            long waitingWorkingSeconds,
            long waitingCalendarSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal meanWaitWorkingSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal meanStageSeconds,
            long maxStageSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal meanQueueLength,
            int maxQueueLength,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal throughputStonesPerHour,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fixedCost,
            BottleneckEvidence bottleneck) {
    }

    /**
     * Why a stage scored as it did. Each signal is between 0 and 1.
     *
     * @param limitingPoolId          the required pool with the highest utilization; null if none is required
     * @param limitingPoolUtilization that pool's utilization over the whole run
     * @param utilizationSignal       the same as a signal in [0, 1]; zero when no pool is required
     * @param waitSignal              mean wait here relative to the largest across stages
     * @param queueSignal             mean queue length here relative to the largest across stages
     * @param saturation              of the time the stage had work, the share spent with work
     *                                waiting and no free slot
     * @param blockedShare            the same blocked time as a share of all working time in the run
     * @param score                   weighted sum of utilization, wait, queue and saturation
     */
    public record BottleneckEvidence(
            UUID limitingPoolId,
            String limitingPoolName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limitingPoolUtilization,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal utilizationSignal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal waitSignal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal queueSignal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal saturation,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal blockedShare,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal score) {
    }

    /**
     * @param capacity      base units in the simulated configuration, before dated windows
     * @param utilization   busy over available, capped at 1; null when nothing was available
     * @param overcommitted jobs already running kept more units than a capacity reduction left
     * @param cost          this pool's share of the employee or machine cost
     */
    public record PoolResult(
            UUID resourceId,
            String name,
            String kind,
            int capacity,
            long busyUnitSeconds,
            long availableUnitSeconds,
            long idleUnitSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal utilization,
            String utilizationNullReason,
            boolean overcommitted,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost) {
    }

    /**
     * @param completesAt when its last batch finishes in the simulation; null if it did not finish
     * @param outcome     {@code ON_TIME}, {@code LATE} or {@code UNFINISHED}
     */
    public record OrderResult(UUID orderId, String orderNumber, Instant completesAt, Instant dueAt, String outcome,
            boolean delayed) {
    }

    /**
     * @param stageId     the primary bottleneck; null when no stage qualifies
     * @param reasonCode  {@code CONSTRAINED_POOL_WITH_QUEUE}, {@code NO_SUSTAINED_QUEUE},
     *                    {@code NO_CLEAR_BOTTLENECK} or {@code NO_WORK}
     * @param explanation one sentence built from the evidence by fixed rules, not by AI
     */
    public record Bottleneck(UUID stageId, String stageCode, String stageName, String reasonCode,
            String explanation,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scoreThreshold,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal saturationThreshold) {
    }

    /** @param state {@code QUEUED}, {@code RUNNING} or {@code NOT_ARRIVED} */
    public record Unfinished(UUID batchId, UUID orderId, int stageSequence, String state) {
    }
}
