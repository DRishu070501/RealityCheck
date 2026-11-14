package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckResult;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckSignals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which stage, if any, is the bottleneck, and keeps the evidence.
 *
 * <p>The slowest stage is not automatically the bottleneck. A stage scores on four signals, each
 * in [0, 1]: how utilized its scarcest pool is, how long work waits there, how long its queue is,
 * and its saturation: of the time it had work, the share spent with work waiting behind full
 * slots. A stage is named only if its score and its saturation both reach their thresholds;
 * otherwise the result says there is no clear bottleneck and why. A long stage with spare capacity
 * and no queue scores low.
 *
 * <p>Saturation, not whole-run utilization, is the gate. Wait and queue are relative to the worst
 * stage, so some stage always has the maximum of both even when its wait was trivial; saturation
 * is absolute and tells a sustained queue from a brief one. Whole-run utilization cannot do that
 * job, because every run has a start-up and a tail in which stages idle for lack of input.
 */
final class BottleneckAnalyzer {

    private static final int SCALE = SimulationMetricsCalculator.RATIO_SCALE;

    /** @param signals one entry per stage, in sequence order */
    record Analysis(UUID primaryStageId, String reasonCode, List<BottleneckSignals> signals) {
    }

    private BottleneckAnalyzer() {
    }

    /**
     * @param utilizationByPool pools that had available time; a pool absent from the map counts as zero
     * @param meanWait          per stage, mean working wait in seconds
     * @param meanQueue         per stage, time-weighted mean queue length
     * @param saturation        per stage, share of its active time with work waiting and no free slot
     * @param blockedShare      per stage, the same blocked time as a share of all working time
     * @param anyWorkDone       whether any job completed
     */
    static Analysis analyse(List<StageSpec> stages, Map<UUID, BigDecimal> utilizationByPool, BigDecimal[] meanWait,
            BigDecimal[] meanQueue, BigDecimal[] saturation, BigDecimal[] blockedShare, boolean anyWorkDone,
            BottleneckConfig config) {
        BigDecimal largestWait = largest(meanWait);
        BigDecimal largestQueue = largest(meanQueue);

        List<BottleneckSignals> signals = new ArrayList<>();
        int top = -1;
        for (int i = 0; i < stages.size(); i++) {
            UUID limitingPool = null;
            BigDecimal limitingUtilization = BigDecimal.ZERO.setScale(SCALE);
            // Requirements are in pool-id order, so a tie goes to the lowest pool id.
            for (RequirementSpec requirement : stages.get(i).requirements()) {
                BigDecimal utilization = utilizationByPool.getOrDefault(requirement.poolId(),
                        BigDecimal.ZERO.setScale(SCALE));
                if (limitingPool == null || utilization.compareTo(limitingUtilization) > 0) {
                    limitingPool = requirement.poolId();
                    limitingUtilization = utilization;
                }
            }
            BigDecimal waitSignal = relative(meanWait[i], largestWait);
            BigDecimal queueSignal = relative(meanQueue[i], largestQueue);
            BigDecimal saturationSignal = saturation[i].setScale(SCALE, RoundingMode.HALF_EVEN);
            BigDecimal score = config.utilizationWeight().multiply(limitingUtilization)
                    .add(config.waitWeight().multiply(waitSignal))
                    .add(config.queueWeight().multiply(queueSignal))
                    .add(config.saturationWeight().multiply(saturationSignal))
                    .setScale(SCALE, RoundingMode.HALF_EVEN);
            signals.add(new BottleneckSignals(limitingPool, limitingPool == null ? null : limitingUtilization,
                    limitingUtilization, waitSignal, queueSignal, saturationSignal,
                    blockedShare[i].setScale(SCALE, RoundingMode.HALF_EVEN), score));
            // Strictly greater, so a tie goes to the earlier stage.
            if (top < 0 || score.compareTo(signals.get(top).score()) > 0) {
                top = i;
            }
        }

        if (!anyWorkDone || top < 0) {
            return new Analysis(null, BottleneckResult.NO_WORK, List.copyOf(signals));
        }
        BottleneckSignals best = signals.get(top);
        if (best.score().compareTo(config.scoreThreshold()) < 0) {
            return new Analysis(null, BottleneckResult.NO_CLEAR_BOTTLENECK, List.copyOf(signals));
        }
        if (best.saturationSignal().compareTo(config.saturationThreshold()) < 0) {
            return new Analysis(null, BottleneckResult.NO_SUSTAINED_QUEUE, List.copyOf(signals));
        }
        return new Analysis(stages.get(top).id(), BottleneckResult.CONSTRAINED_POOL_WITH_QUEUE,
                List.copyOf(signals));
    }

    private static BigDecimal largest(BigDecimal[] values) {
        BigDecimal largest = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            largest = largest.max(value);
        }
        return largest;
    }

    /** {@code value / largest}, or zero when nothing waited anywhere. */
    private static BigDecimal relative(BigDecimal value, BigDecimal largest) {
        return largest.signum() == 0 ? BigDecimal.ZERO.setScale(SCALE)
                : value.divide(largest, SCALE, RoundingMode.HALF_EVEN);
    }
}
