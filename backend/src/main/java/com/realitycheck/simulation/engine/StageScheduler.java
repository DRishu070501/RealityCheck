package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Starts queued jobs. Runs once per simulated instant, after every other event at that instant,
 * so it sees all the units released and all the work that arrived.
 */
final class StageScheduler {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);

    private final SimulationContext context;
    private final SimulationState state;
    private final SimulationEventQueue events;

    StageScheduler(SimulationContext context, SimulationState state, SimulationEventQueue events) {
        this.context = context;
        this.state = state;
        this.events = events;
    }

    /**
     * Stages are scanned last to first, so that when pools are shared, work nearer the end of the
     * workflow is served first. Within a stage, jobs start in dispatch order until one cannot.
     *
     * <p>Allocation is atomic: a job's whole set of units is checked and then taken in one step
     * with no event in between. A job never holds some of what it needs while waiting for the rest.
     */
    void dispatch(long now) {
        if (!context.calendar.isWorking(now)) {
            return;
        }
        for (int index = context.stages.size() - 1; index >= 0; index--) {
            StageSpec stage = context.stage(index);
            Iterator<ProductionJob> waiting = state.queues.get(index).iterator();
            // Every job at a stage needs the same set, so once one cannot start none behind it can.
            while (waiting.hasNext() && state.freeSlots(stage) > 0) {
                ProductionJob job = waiting.next();
                waiting.remove();
                start(job, stage, now);
            }
        }
    }

    private void start(ProductionJob job, StageSpec stage, long now) {
        List<ResourceAllocation> allocations = new ArrayList<>();
        for (RequirementSpec requirement : stage.requirements()) {
            state.pools.get(requirement.poolId()).allocate(requirement.unitsPerSlot());
            allocations.add(new ResourceAllocation(requirement.poolId(), requirement.unitsPerSlot()));
        }
        job.allocations = List.copyOf(allocations);
        job.startTime = now;
        job.workSeconds = workSeconds(stage, job.inputStones);
        job.completionTime = context.stageWorkingTime.get(job.stageIndex)
                .addWorkingSeconds(now, job.workSeconds, context.horizonEnd);
        state.running.put(job.batch.id(), job);
        events.schedule(job.completionTime, SimulationEvent.Type.STAGE_COMPLETED, job.batch.id());
        state.trace(now, TraceType.JOB_STARTED, job.batch, stage.id(), null, job.inputStones, job.inputCarats, null);
    }

    /**
     * Working seconds one slot needs for a job: setup, plus the stones at the slot's hourly rate,
     * rounded up to a whole second.
     */
    static long workSeconds(StageSpec stage, BigDecimal stones) {
        long processing = stones.multiply(SECONDS_PER_HOUR)
                .divide(stage.stonesPerHourPerSlot(), 0, RoundingMode.CEILING)
                .longValueExact();
        return stage.setupSeconds() + processing;
    }
}
