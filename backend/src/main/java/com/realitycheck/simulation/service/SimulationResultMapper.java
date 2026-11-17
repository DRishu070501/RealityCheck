package com.realitycheck.simulation.service;

import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.simulation.api.SimulationDtos;
import com.realitycheck.simulation.api.SimulationDtos.Bottleneck;
import com.realitycheck.simulation.api.SimulationDtos.BottleneckEvidence;
import com.realitycheck.simulation.api.SimulationDtos.Cost;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import com.realitycheck.simulation.api.SimulationDtos.Unfinished;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckResult;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckSignals;
import com.realitycheck.simulation.engine.SimulationResult.SummaryMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns an engine result into the API response. It copies numbers and adds names; it computes
 * nothing new. The one sentence it writes, the bottleneck explanation, is assembled from the
 * engine's own evidence by fixed rules, so it can never say more than the numbers do.
 */
public final class SimulationResultMapper {

    private SimulationResultMapper() {
    }

    /** Names, currency and pool capacities come from the snapshot the result was computed from. */
    public static SimulationResultResponse toResponse(UUID factoryId, Snapshot snapshot, SimulationResult result) {
        return toResponse(factoryId, snapshot.currencyCode(), SnapshotInputAdapter.stageNames(snapshot),
                SnapshotInputAdapter.poolCapacities(snapshot), result);
    }

    /**
     * @param stageNames     display names of the stages, by id
     * @param poolCapacities base capacity of each pool in the simulated configuration
     */
    public static SimulationResultResponse toResponse(UUID factoryId, String currencyCode,
            Map<UUID, String> stageNames, Map<UUID, Integer> poolCapacities, SimulationResult result) {
        Map<UUID, String> poolNames = result.pools().stream()
                .collect(Collectors.toMap(SimulationResult.PoolResult::poolId, SimulationResult.PoolResult::name));

        List<StageResult> stages = result.stages().stream()
                .map(stage -> new StageResult(stage.stageId(), stage.code(),
                        stageNames.getOrDefault(stage.stageId(), stage.code()), stage.sequence(),
                        stage.jobsCompleted(), stage.inputStones(), stage.outputStones(), stage.lossStones(),
                        stage.inputCarats(), stage.outputCarats(), stage.lossCarats(), stage.processingSeconds(),
                        stage.waitingWorkingSeconds(), stage.waitingCalendarSeconds(),
                        stage.meanWaitWorkingSeconds(), stage.meanStageSeconds(), stage.maxStageSeconds(),
                        stage.meanQueueLength(), stage.maxQueueLength(), stage.throughputStonesPerHour(),
                        stage.fixedCost(), toEvidence(stage.bottleneck(), poolNames)))
                .toList();

        List<PoolResult> pools = result.pools().stream()
                .map(pool -> new PoolResult(pool.poolId(), pool.name(), pool.kind().name(),
                        poolCapacities.getOrDefault(pool.poolId(), 0), pool.busyUnitSeconds(),
                        pool.availableUnitSeconds(), pool.idleUnitSeconds(), pool.utilization(),
                        pool.utilizationNullReason(), pool.overcommitted(), pool.cost()))
                .toList();

        List<OrderResult> orders = result.orders().stream()
                .map(order -> new OrderResult(order.orderId(), order.orderNumber(), instant(order.completionTime()),
                        instant(order.dueTime()), order.outcome().name(), order.delayed()))
                .toList();

        SummaryMetrics s = result.summary();
        Summary summary = new Summary(s.elapsedSeconds(), s.workingSecondsInWindow(), s.processingSeconds(),
                s.waitingWorkingSeconds(), s.waitingCalendarSeconds(), s.inputStones(), s.outputStones(),
                s.outputStonesRounded(), s.inputCarats(), s.outputCarats(), s.stoneYield(), s.caratYield(),
                s.throughputStonesPerHour(), s.machineUtilization(), s.employeeUtilization(),
                new Cost(s.cost().employee(), s.cost().machine(), s.cost().fixed(), s.cost().total(),
                        s.cost().perOutputStone(), currencyCode),
                s.ordersTotal(), s.ordersCompleted(), s.ordersDelayed(), s.ordersWithDueDate(),
                s.onTimeDeliveryRate(), s.parkingSeconds(), s.waitingStageSeconds(), s.jobWorkSeconds(),
                s.handlingSeconds(),
                s.batchLeadMedianSeconds(), s.batchManufacturingMedianSeconds());

        return new SimulationResultResponse(factoryId, Instant.ofEpochSecond(result.forkTime()),
                Instant.ofEpochSecond(result.endTime()), result.algorithmVersion(),
                result.terminationReason().name(), result.eventsProcessed(), summary, stages, pools, orders,
                toBottleneck(result, stages),
                result.unfinished().stream()
                        .map(u -> new Unfinished(u.batchId(), u.orderId(), u.stageSequence(), u.state()))
                        .toList(),
                SimulationDtos.SIMULATED);
    }

    private static BottleneckEvidence toEvidence(BottleneckSignals signals, Map<UUID, String> poolNames) {
        return new BottleneckEvidence(signals.limitingPoolId(),
                signals.limitingPoolId() == null ? null : poolNames.get(signals.limitingPoolId()),
                signals.limitingPoolUtilization(), signals.utilizationSignal(), signals.waitSignal(), signals.queueSignal(),
                signals.saturationSignal(), signals.blockedShare(), signals.score());
    }

    private static Bottleneck toBottleneck(SimulationResult result, List<StageResult> stages) {
        BottleneckResult bottleneck = result.bottleneck();
        StageResult primary = stages.stream()
                .filter(stage -> stage.stageId().equals(bottleneck.primaryStageId()))
                .findFirst().orElse(null);
        // When no stage qualifies, the explanation still names the one that came closest.
        StageResult closest = primary != null ? primary : stages.stream()
                .max((a, b) -> a.bottleneck().score().compareTo(b.bottleneck().score()))
                .orElse(null);
        return new Bottleneck(bottleneck.primaryStageId(), primary == null ? null : primary.code(),
                primary == null ? null : primary.name(), bottleneck.reasonCode(),
                explain(bottleneck.reasonCode(), closest), bottleneck.configUsed().scoreThreshold(),
                bottleneck.configUsed().saturationThreshold());
    }

    static String explain(String reasonCode, StageResult stage) {
        if (stage == null || BottleneckResult.NO_WORK.equals(reasonCode)) {
            return "No work was processed, so there is nothing to constrain.";
        }
        BottleneckEvidence evidence = stage.bottleneck();
        String waited = "work waited behind full slots for " + percent(evidence.saturation())
                + " of the time the stage had work (mean wait " + hours(stage.meanWaitWorkingSeconds())
                + " working hours, queue up to " + stage.maxQueueLength() + ")";
        String pool = evidence.limitingPoolName() == null ? ""
                : "; its most used pool, " + evidence.limitingPoolName() + ", was at "
                        + percent(evidence.limitingPoolUtilization()) + " over the whole run";
        return switch (reasonCode) {
            case BottleneckResult.CONSTRAINED_POOL_WITH_QUEUE ->
                    stage.name() + " is the bottleneck: " + waited + pool + ".";
            case BottleneckResult.NO_SUSTAINED_QUEUE ->
                    "No sustained bottleneck. " + stage.name() + " had the longest wait, but " + waited
                            + ", which is too brief to call a constraint" + pool + ".";
            default -> "No clear bottleneck. " + stage.name() + " scored highest, but " + waited + pool + ".";
        };
    }

    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static String hours(BigDecimal seconds) {
        return seconds.divide(BigDecimal.valueOf(3_600), 1, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static Instant instant(Long epochSecond) {
        return epochSecond == null ? null : Instant.ofEpochSecond(epochSecond);
    }
}
