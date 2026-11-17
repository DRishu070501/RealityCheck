package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.TraceEvent;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Everything that changes during a run. Created for one run and never shared. */
final class SimulationState {

    final TreeMap<UUID, ResourcePool> pools = new TreeMap<>();
    /** One queue per stage, kept in dispatch order. */
    final List<TreeSet<ProductionJob>> queues = new ArrayList<>();
    /** Jobs holding resources, by batch id. */
    final TreeMap<UUID, ProductionJob> running = new TreeMap<>();
    /** Batches that have not yet arrived, by batch id. */
    final TreeMap<UUID, BatchSpec> notArrived = new TreeMap<>();
    final TreeMap<UUID, ProductionBatch> batches = new TreeMap<>();
    final List<StageExecution> executions = new ArrayList<>();
    /** When each batch finished the last stage of its path. */
    final TreeMap<UUID, Long> batchFinishedAt = new TreeMap<>();
    /** What finished batches left their last stage with: the run's output. */
    BigDecimal finishedStones = BigDecimal.ZERO.setScale(4);
    BigDecimal finishedCarats = BigDecimal.ZERO.setScale(4);
    /** When each batch became active in the run: the fork, or its arrival. */
    final TreeMap<UUID, Long> batchEnteredAt = new TreeMap<>();
    /** Calendar seconds each batch spent at PARKING stages within the run. */
    final TreeMap<UUID, Long> batchParkedSeconds = new TreeMap<>();
    /** Calendar seconds, summed over batches, held at stages of each non-working kind within the run. */
    long parkingSeconds;
    long waitingStageSeconds;
    long jobWorkSeconds;
    /** Calendar seconds, summed over batches, held at working stages after processing for handling. */
    long handlingSeconds;
    final TreeMap<UUID, Long> orderCompletedAt = new TreeMap<>();
    final List<TraceEvent> trace = new ArrayList<>();

    /** Per stage: queue length integrated over working seconds. */
    final long[] queueIntegral;
    /** Per stage: working seconds with work waiting and no free slot. */
    final long[] blockedSeconds;
    /** Per stage: working seconds with any job queued or running there. */
    final long[] activeSeconds;
    final int[] maxQueueLength;

    long eventsProcessed;
    boolean dispatchPending;
    /** The last stage completion processed, or the fork time. */
    long lastCompletionTime;
    private long lastAccrualTime;

    SimulationState(SimulationContext context) {
        int stageCount = context.stages.size();
        for (int i = 0; i < stageCount; i++) {
            queues.add(new TreeSet<>(ProductionJob.DISPATCH_ORDER));
        }
        queueIntegral = new long[stageCount];
        blockedSeconds = new long[stageCount];
        activeSeconds = new long[stageCount];
        maxQueueLength = new int[stageCount];
        context.capacityProfiles.forEach((id, profile) ->
                pools.put(id, new ResourcePool(id, profile.capacityAt(context.forkTime))));
        lastCompletionTime = context.forkTime;
        lastAccrualTime = context.forkTime;
    }

    /**
     * Adds the working time since the last event to the queue-length and blocked integrals.
     * Nothing changes between events, so the integrals are exact.
     */
    void accrueUntil(long time, SimulationContext context) {
        long working = context.calendar.workingSecondsBetween(lastAccrualTime, time);
        if (working > 0) {
            boolean[] hasRunningJob = new boolean[queues.size()];
            for (ProductionJob job : running.values()) {
                // Only processing makes a stage active: not a batch held for handling after processing, nor
                // one held at a parking, waiting or job-work stage (spec 11). Neither uses the stage's slots.
                if (!job.handling && context.stage(job.stageIndex).kind() == SimulationInput.StageKind.WORKING) {
                    hasRunningJob[job.stageIndex] = true;
                }
            }
            for (int i = 0; i < queues.size(); i++) {
                int waiting = queues.get(i).size();
                queueIntegral[i] += waiting * working;
                if (waiting > 0 || hasRunningJob[i]) {
                    activeSeconds[i] += working;
                }
                if (waiting > 0 && freeSlots(context.stage(i)) == 0) {
                    blockedSeconds[i] += working;
                }
            }
        }
        lastAccrualTime = time;
    }

    void sampleQueueLengths() {
        for (int i = 0; i < queues.size(); i++) {
            maxQueueLength[i] = Math.max(maxQueueLength[i], queues.get(i).size());
        }
    }

    /** How many more jobs the stage could start right now; unlimited when it needs no resources. */
    int freeSlots(StageSpec stage) {
        int slots = Integer.MAX_VALUE;
        for (RequirementSpec requirement : stage.requirements()) {
            slots = Math.min(slots, pools.get(requirement.poolId()).free() / requirement.unitsPerSlot());
        }
        return slots;
    }

    boolean hasUnfinishedWork() {
        return !running.isEmpty() || !notArrived.isEmpty() || queues.stream().anyMatch(queue -> !queue.isEmpty());
    }

    void trace(long time, TraceType type, ProductionBatch batch, UUID stageId, UUID poolId, BigDecimal stones,
            BigDecimal carats, Integer capacity) {
        trace.add(new TraceEvent(trace.size(), time, type, batch == null ? null : batch.id(),
                batch == null ? null : batch.orderId(), stageId, poolId, stones, carats, capacity));
    }
}
