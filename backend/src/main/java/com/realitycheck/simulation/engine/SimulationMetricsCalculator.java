package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.CapacityProfile.Segment;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.ResourceKind;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckResult;
import com.realitycheck.simulation.engine.SimulationResult.BottleneckSignals;
import com.realitycheck.simulation.engine.SimulationResult.CostBreakdown;
import com.realitycheck.simulation.engine.SimulationResult.OrderOutcome;
import com.realitycheck.simulation.engine.SimulationResult.OrderResult;
import com.realitycheck.simulation.engine.SimulationResult.PoolResult;
import com.realitycheck.simulation.engine.SimulationResult.StageResult;
import com.realitycheck.simulation.engine.SimulationResult.SummaryMetrics;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import com.realitycheck.simulation.engine.SimulationResult.UnfinishedJob;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns the final state of a run into its metrics. Pure: it reads the context and state and
 * changes neither. Definitions are in .kiro/steering/simulation-rules.md, section 11.
 *
 * <p>With T0 the fork and T1 the last stage completion, every metric describes the window
 * {@code [T0, T1]}. Scales: quantities 4, ratios 6, means 4, money 2; all divisions round half-even.
 */
final class SimulationMetricsCalculator {

    static final int QUANTITY_SCALE = 4;
    static final int RATIO_SCALE = 6;
    static final int MEAN_SCALE = 4;
    static final int MONEY_SCALE = 2;

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);

    private SimulationMetricsCalculator() {
    }

    static SimulationResult calculate(SimulationContext context, SimulationState state, TerminationReason reason) {
        long t0 = context.forkTime;
        long t1 = state.lastCompletionTime;
        long workingSeconds = context.calendar.workingSecondsBetween(t0, t1);
        int stageCount = context.stages.size();

        // ---- per stage, from the finished jobs ----
        int[] jobs = new int[stageCount];
        long[] processing = new long[stageCount];
        long[] waitingWorking = new long[stageCount];
        long[] waitingCalendar = new long[stageCount];
        long[] stageSecondsTotal = new long[stageCount];
        long[] stageSecondsMax = new long[stageCount];
        BigDecimal[] inStones = zeros(stageCount);
        BigDecimal[] outStones = zeros(stageCount);
        BigDecimal[] inCarats = zeros(stageCount);
        BigDecimal[] outCarats = zeros(stageCount);
        TreeMap<UUID, Long> busy = new TreeMap<>();
        context.pools.keySet().forEach(poolId -> busy.put(poolId, 0L));

        for (StageExecution execution : state.executions) {
            int i = execution.stageIndex();
            jobs[i]++;
            processing[i] += execution.workSeconds();
            waitingWorking[i] += execution.waitingWorkingSeconds();
            waitingCalendar[i] += Math.max(0, Math.max(execution.startTime(), t0) - execution.waitFrom());
            stageSecondsTotal[i] += execution.stageWorkingSeconds();
            stageSecondsMax[i] = Math.max(stageSecondsMax[i], execution.stageWorkingSeconds());
            inStones[i] = inStones[i].add(execution.inputStones());
            outStones[i] = outStones[i].add(execution.outputStones());
            inCarats[i] = inCarats[i].add(execution.inputCarats());
            outCarats[i] = outCarats[i].add(execution.outputCarats());
            for (ResourceAllocation allocation : execution.allocations()) {
                busy.merge(allocation.poolId(), allocation.units() * execution.workSeconds(), Long::sum);
            }
        }
        // Jobs still running when a limit ended the run were busy up to T1.
        for (ProductionJob job : state.running.values()) {
            long from = Math.max(job.startTime, t0);
            long worked = context.stageWorkingTime.get(job.stageIndex).workingSecondsBetween(from, t1);
            for (ResourceAllocation allocation : job.allocations) {
                busy.merge(allocation.poolId(), allocation.units() * worked, Long::sum);
            }
        }

        // ---- pools ----
        List<PoolResult> poolResults = new ArrayList<>();
        Map<UUID, BigDecimal> utilizationByPool = new TreeMap<>();
        BigDecimal employeeCost = BigDecimal.ZERO;
        BigDecimal machineCost = BigDecimal.ZERO;
        long[] usedByKind = new long[ResourceKind.values().length];
        long[] availableByKind = new long[ResourceKind.values().length];
        for (PoolSpec pool : context.pools.values()) {
            long available = 0;
            for (Segment segment : context.capacityProfiles.get(pool.id()).segments(t0, t1)) {
                available += segment.capacity()
                        * context.calendar.workingSecondsBetween(segment.from(), segment.to());
            }
            long busySeconds = busy.get(pool.id());
            BigDecimal utilization = available == 0 ? null
                    : ratio(Math.min(busySeconds, available), available);
            if (utilization != null) {
                utilizationByPool.put(pool.id(), utilization);
            }
            // People are paid for the time they are scheduled; machines cost while they run.
            long costedSeconds = pool.kind() == ResourceKind.EMPLOYEE ? available : busySeconds;
            BigDecimal cost = pool.hourlyCost().multiply(BigDecimal.valueOf(costedSeconds))
                    .divide(SECONDS_PER_HOUR, MONEY_SCALE, RoundingMode.HALF_EVEN);
            if (pool.kind() == ResourceKind.EMPLOYEE) {
                employeeCost = employeeCost.add(cost);
            } else {
                machineCost = machineCost.add(cost);
            }
            usedByKind[pool.kind().ordinal()] += Math.min(busySeconds, available);
            availableByKind[pool.kind().ordinal()] += available;
            poolResults.add(new PoolResult(pool.id(), pool.kind(), pool.name(), busySeconds, available,
                    Math.max(0, available - busySeconds), utilization,
                    utilization == null ? PoolResult.NO_AVAILABLE_TIME : null,
                    busySeconds > available || state.pools.get(pool.id()).wasOvercommitted(), cost));
        }

        // ---- bottleneck evidence, then stage results ----
        BigDecimal[] meanWait = new BigDecimal[stageCount];
        BigDecimal[] meanQueue = new BigDecimal[stageCount];
        BigDecimal[] blockedShare = new BigDecimal[stageCount];
        BigDecimal[] saturation = new BigDecimal[stageCount];
        for (int i = 0; i < stageCount; i++) {
            meanWait[i] = jobs[i] == 0 ? zero(MEAN_SCALE) : mean(waitingWorking[i], jobs[i]);
            meanQueue[i] = workingSeconds == 0 ? zero(MEAN_SCALE)
                    : BigDecimal.valueOf(state.queueIntegral[i])
                            .divide(BigDecimal.valueOf(workingSeconds), MEAN_SCALE, RoundingMode.HALF_EVEN);
            blockedShare[i] = workingSeconds == 0 ? zero(RATIO_SCALE)
                    : ratio(Math.min(state.blockedSeconds[i], workingSeconds), workingSeconds);
            saturation[i] = state.activeSeconds[i] == 0 ? zero(RATIO_SCALE)
                    : ratio(Math.min(state.blockedSeconds[i], state.activeSeconds[i]), state.activeSeconds[i]);
        }
        BottleneckAnalyzer.Analysis analysis = BottleneckAnalyzer.analyse(context.stages, utilizationByPool,
                meanWait, meanQueue, saturation, blockedShare, !state.executions.isEmpty(),
                context.input.bottleneck());

        List<StageResult> stageResults = new ArrayList<>();
        BigDecimal fixedCost = BigDecimal.ZERO;
        for (int i = 0; i < stageCount; i++) {
            StageSpec stage = context.stage(i);
            BigDecimal stageFixed = stage.fixedCostPerJob().multiply(BigDecimal.valueOf(jobs[i]))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
            fixedCost = fixedCost.add(stageFixed);
            BottleneckSignals signals = analysis.signals().get(i);
            stageResults.add(new StageResult(stage.id(), stage.code(), stage.sequence(), jobs[i],
                    inStones[i], outStones[i], inStones[i].subtract(outStones[i]),
                    inCarats[i], outCarats[i], inCarats[i].subtract(outCarats[i]),
                    processing[i], waitingWorking[i], waitingCalendar[i], meanWait[i],
                    jobs[i] == 0 ? zero(MEAN_SCALE) : mean(stageSecondsTotal[i], jobs[i]), stageSecondsMax[i],
                    meanQueue[i], state.maxQueueLength[i], perWorkingHour(outStones[i], workingSeconds), stageFixed,
                    signals));
        }

        // ---- orders ----
        List<OrderResult> orderResults = new ArrayList<>();
        int completed = 0;
        int delayed = 0;
        int withDueDate = 0;
        int onTime = 0;
        for (OrderSpec order : context.orders.values()) {
            Long completionTime = state.orderCompletedAt.get(order.id());
            OrderOutcome outcome;
            if (completionTime == null) {
                outcome = OrderOutcome.UNFINISHED;
            } else if (order.dueTime() != null && completionTime > order.dueTime()) {
                outcome = OrderOutcome.LATE;
            } else {
                outcome = OrderOutcome.ON_TIME;
            }
            boolean isDelayed = outcome == OrderOutcome.LATE
                    || (outcome == OrderOutcome.UNFINISHED && order.dueTime() != null && order.dueTime() < t1);
            completed += completionTime == null ? 0 : 1;
            delayed += isDelayed ? 1 : 0;
            if (order.dueTime() != null) {
                withDueDate++;
                onTime += outcome == OrderOutcome.ON_TIME ? 1 : 0;
            }
            orderResults.add(new OrderResult(order.id(), order.orderNumber(), completionTime, order.dueTime(),
                    outcome, isDelayed));
        }

        // ---- summary ----
        BigDecimal inputStones = zero(QUANTITY_SCALE);
        BigDecimal inputCarats = zero(QUANTITY_SCALE);
        for (BatchSpec batch : context.batches) {
            inputStones = inputStones.add(batch.stones().setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN));
            inputCarats = inputCarats.add(batch.carats().setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN));
        }
        // What finished batches produced at the end of their own paths. Without routes every path
        // ends at the last stage, so this equals that stage's output.
        BigDecimal outputStones = state.finishedStones;
        BigDecimal outputCarats = state.finishedCarats;
        BigDecimal totalCost = employeeCost.add(machineCost).add(fixedCost);
        CostBreakdown cost = new CostBreakdown(employeeCost.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN),
                machineCost.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN),
                fixedCost.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN),
                totalCost.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN),
                outputStones.signum() == 0 ? null
                        : totalCost.divide(outputStones, MEAN_SCALE, RoundingMode.HALF_EVEN));

        SummaryMetrics summary = new SummaryMetrics(
                t1 - t0,
                workingSeconds,
                sum(processing),
                sum(waitingWorking),
                sum(waitingCalendar),
                inputStones,
                outputStones,
                outputStones.setScale(0, RoundingMode.HALF_EVEN),
                inputCarats,
                outputCarats,
                inputStones.signum() == 0 ? null
                        : outputStones.divide(inputStones, RATIO_SCALE, RoundingMode.HALF_EVEN),
                inputCarats.signum() == 0 ? null
                        : outputCarats.divide(inputCarats, RATIO_SCALE, RoundingMode.HALF_EVEN),
                perWorkingHour(outputStones, workingSeconds),
                kindUtilization(usedByKind, availableByKind, ResourceKind.MACHINE),
                kindUtilization(usedByKind, availableByKind, ResourceKind.EMPLOYEE),
                cost,
                context.orders.size(),
                completed,
                delayed,
                withDueDate,
                withDueDate == 0 ? null : ratio(onTime, withDueDate));

        return new SimulationResult(SimulationEngine.ALGORITHM_VERSION, t0, t1, reason, state.eventsProcessed,
                summary, List.copyOf(stageResults), List.copyOf(poolResults), List.copyOf(orderResults),
                new BottleneckResult(analysis.primaryStageId(), analysis.reasonCode(), context.input.bottleneck()),
                unfinished(context, state), List.copyOf(state.trace));
    }

    private static List<UnfinishedJob> unfinished(SimulationContext context, SimulationState state) {
        TreeMap<UUID, UnfinishedJob> byBatch = new TreeMap<>();
        for (ProductionJob job : state.running.values()) {
            byBatch.put(job.batch.id(), new UnfinishedJob(job.batch.id(), job.batch.orderId(),
                    job.stageIndex + 1, "RUNNING"));
        }
        for (var queue : state.queues) {
            for (ProductionJob job : queue) {
                byBatch.put(job.batch.id(), new UnfinishedJob(job.batch.id(), job.batch.orderId(),
                        job.stageIndex + 1, "QUEUED"));
            }
        }
        for (BatchSpec batch : state.notArrived.values()) {
            byBatch.put(batch.id(), new UnfinishedJob(batch.id(), batch.orderId(), batch.stageSequence(),
                    "NOT_ARRIVED"));
        }
        return List.copyOf(byBatch.values());
    }

    private static BigDecimal kindUtilization(long[] used, long[] available, ResourceKind kind) {
        long denominator = available[kind.ordinal()];
        return denominator == 0 ? null : ratio(used[kind.ordinal()], denominator);
    }

    /** Stones per working hour; null when there was no working time. */
    private static BigDecimal perWorkingHour(BigDecimal stones, long workingSeconds) {
        return workingSeconds == 0 ? null
                : stones.multiply(SECONDS_PER_HOUR)
                        .divide(BigDecimal.valueOf(workingSeconds), MEAN_SCALE, RoundingMode.HALF_EVEN);
    }

    static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATIO_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal mean(long total, int count) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), MEAN_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale);
    }

    private static BigDecimal[] zeros(int count) {
        BigDecimal[] values = new BigDecimal[count];
        java.util.Arrays.fill(values, zero(QUANTITY_SCALE));
        return values;
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) {
            total += value;
        }
        return total;
    }
}
