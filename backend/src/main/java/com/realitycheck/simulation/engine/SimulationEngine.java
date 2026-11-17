package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.StageKind;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.simulation.engine.SimulationResult.TerminationReason;
import com.realitycheck.simulation.engine.SimulationResult.TraceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A deterministic discrete-event simulation of a factory: a pure function from
 * {@link SimulationInput} to {@link SimulationResult}. The same input always gives an equal
 * result. It reads no clock, uses no randomness, no threads, no I/O and no floating point, and
 * depends on nothing but the JDK.
 *
 * <p>The rules it implements are in .kiro/steering/simulation-rules.md. Any change that alters
 * output for some input must change {@link #ALGORITHM_VERSION}.
 */
public final class SimulationEngine {

    /**
     * 2.0.0 (spec 11): routes, and stages that hold a batch for a measured delay (parking, waiting,
     * job work), with their times and batch lead times in the summary. An input with no routes and only
     * working stages gives the same values as 1.0.0 for every measure 1.0.0 had.
     */
    public static final String ALGORITHM_VERSION = "2.0.0";

    private static final int QUANTITY_SCALE = 4;

    /**
     * @throws InvalidSimulationInputException if the input describes an impossible factory.
     *         Reaching a simulation limit is not an error: it is reported in the result.
     */
    /**
     * The input validation {@link #run} performs first, without running anything: lets callers
     * report problems before a run is submitted.
     *
     * @return every problem found; empty when the input can be simulated
     */
    public java.util.List<InvalidSimulationInputException.Problem> problems(SimulationInput input) {
        try {
            InputValidator.validate(input);
            return java.util.List.of();
        } catch (InvalidSimulationInputException ex) {
            return ex.problems();
        }
    }

    public SimulationResult run(SimulationInput input) {
        InputValidator.validate(input);
        SimulationContext context = new SimulationContext(input);
        SimulationState state = new SimulationState(context);
        SimulationClock clock = new SimulationClock(context.forkTime);
        SimulationEventQueue events = new SimulationEventQueue();
        StageScheduler scheduler = new StageScheduler(context, state, events);

        initialise(context, state, events);

        TerminationReason reason = TerminationReason.COMPLETED;
        while (!events.isEmpty()) {
            SimulationEvent event = events.peek();
            if (event.time() > context.horizonEnd) {
                reason = TerminationReason.HORIZON_REACHED;
                break;
            }
            if (state.eventsProcessed == input.limits().maxEvents()) {
                reason = TerminationReason.EVENT_LIMIT_REACHED;
                break;
            }
            events.poll();
            state.eventsProcessed++;
            state.accrueUntil(event.time(), context);
            clock.advanceTo(event.time());
            long now = clock.now();

            switch (event.type()) {
                case RESOURCE_CAPACITY_CHANGE -> {
                    ResourcePool pool = state.pools.get(event.subjectId());
                    pool.setCapacity(context.capacityProfiles.get(pool.id()).capacityAt(now));
                    state.trace(now, TraceType.CAPACITY_CHANGED, null, null, pool.id(), null, null, pool.capacity());
                    requestDispatch(state, events, now);
                }
                case STAGE_COMPLETED -> {
                    complete(state.running.remove(event.subjectId()), context, state, events, now);
                    requestDispatch(state, events, now);
                }
                case SHIFT_START -> {
                    scheduleNextShiftStart(context, state, events, now);
                    requestDispatch(state, events, now);
                }
                case JOB_ARRIVAL -> {
                    BatchSpec arriving = state.notArrived.remove(event.subjectId());
                    enqueue(new ProductionJob(state.batches.get(arriving.id()), arriving.stageSequence() - 1,
                            scaled(arriving.stones()), scaled(arriving.carats()), now), context, state, events, now);
                    requestDispatch(state, events, now);
                }
                case DISPATCH_ATTEMPT -> {
                    state.dispatchPending = false;
                    scheduler.dispatch(now);
                    state.sampleQueueLengths();
                }
                default -> throw new IllegalStateException("Unknown event type " + event.type());
            }
        }
        if (!state.hasUnfinishedWork()) {
            // A limit may be hit by a leftover wake-up after the last job finished; the work is done.
            reason = TerminationReason.COMPLETED;
        } else if (reason == TerminationReason.COMPLETED) {
            // Nothing left to happen but work unfinished. Shift starts keep being scheduled while
            // work remains, so this should be unreachable; report it as work that never finished.
            reason = TerminationReason.HORIZON_REACHED;
        }
        return SimulationMetricsCalculator.calculate(context, state, reason);
    }

    private static void initialise(SimulationContext context, SimulationState state, SimulationEventQueue events) {
        long forkTime = context.forkTime;
        context.capacityProfiles.forEach((poolId, profile) -> {
            for (long boundary : profile.boundariesBetween(forkTime, context.horizonEnd)) {
                events.schedule(boundary, SimulationEvent.Type.RESOURCE_CAPACITY_CHANGE, poolId);
            }
        });

        for (BatchSpec spec : context.batches) {
            OrderSpec order = context.orders.get(spec.orderId());
            ProductionBatch batch = new ProductionBatch(spec.id(), spec.orderId(), order.priority(), order.dueTime(),
                    context.pathFor(spec));
            state.batches.put(batch.id(), batch);
            state.batchEnteredAt.put(batch.id(), spec.state() == BatchEntryState.FUTURE_ARRIVAL
                    ? Math.max(spec.arrivalTime(), forkTime) : forkTime);
            int stageIndex = spec.stageSequence() - 1;
            if (spec.state() == BatchEntryState.IN_PROGRESS && context.stage(stageIndex).kind() != StageKind.WORKING) {
                // Held at a parking, waiting or job-work stage at the fork: its delay counts from when it began.
                hold(new ProductionJob(batch, stageIndex, scaled(spec.stones()), scaled(spec.carats()),
                        spec.startTime()), context, state, events, forkTime);
                continue;
            }
            switch (spec.state()) {
                case IN_PROGRESS -> {
                    StageSpec stage = context.stage(stageIndex);
                    ProductionJob job = new ProductionJob(batch, stageIndex, scaled(spec.stones()),
                            scaled(spec.carats()), spec.startTime());
                    job.inProgressAtFork = true;
                    job.startTime = spec.startTime();
                    List<ResourceAllocation> held = new ArrayList<>();
                    for (RequirementSpec requirement : stage.requirements()) {
                        // It already has these units; a pool reduced since then is simply over-committed.
                        state.pools.get(requirement.poolId()).allocateHeld(requirement.unitsPerSlot());
                        held.add(new ResourceAllocation(requirement.poolId(), requirement.unitsPerSlot()));
                    }
                    job.allocations = List.copyOf(held);
                    WorkingCalendar workingTime = context.stageWorkingTime.get(stageIndex);
                    long total = StageScheduler.workSeconds(stage, job.inputStones);
                    long done = workingTime.workingSecondsBetween(spec.startTime(), forkTime);
                    job.workSeconds = Math.max(0, total - done);
                    job.completionTime = workingTime.addWorkingSeconds(forkTime, job.workSeconds, context.horizonEnd);
                    state.running.put(batch.id(), job);
                    events.schedule(job.completionTime, SimulationEvent.Type.STAGE_COMPLETED, batch.id());
                }
                case QUEUED -> enqueue(new ProductionJob(batch, stageIndex, scaled(spec.stones()),
                        scaled(spec.carats()), spec.queueEntryTime()), context, state, events, forkTime);
                case FUTURE_ARRIVAL -> {
                    state.notArrived.put(batch.id(), spec);
                    events.schedule(Math.max(spec.arrivalTime(), forkTime), SimulationEvent.Type.JOB_ARRIVAL,
                            batch.id());
                }
                default -> throw new IllegalStateException("Unknown batch state " + spec.state());
            }
        }
        requestDispatch(state, events, forkTime);
        scheduleNextShiftStart(context, state, events, forkTime);
    }

    private static void complete(ProductionJob job, SimulationContext context, SimulationState state,
            SimulationEventQueue events, long now) {
        if (job == null) {
            throw new IllegalStateException("A stage completed for a batch that is not running");
        }
        for (ResourceAllocation allocation : job.allocations) {
            state.pools.get(allocation.poolId()).release(allocation.units());
        }
        StageSpec stage = context.stage(job.stageIndex);
        // Yield is applied exactly; stones stay fractional and nothing is truncated between stages.
        BigDecimal outputStones = job.inputStones.multiply(stage.stoneYield())
                .setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal outputCarats = job.inputCarats.multiply(stage.caratYield())
                .setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN);

        // Waiting before the fork is history, not simulation; work in progress at the fork did not wait.
        long waitFrom = job.inProgressAtFork ? context.forkTime : Math.max(job.queueEntryTime, context.forkTime);
        long simulatedStart = job.inProgressAtFork ? context.forkTime : job.startTime;
        state.executions.add(new StageExecution(job.batch, job.stageIndex, waitFrom, job.startTime, now,
                context.calendar.workingSecondsBetween(waitFrom, simulatedStart), job.workSeconds,
                context.calendar.workingSecondsBetween(waitFrom, now),
                job.inputStones, outputStones, job.inputCarats, outputCarats, job.allocations));
        state.lastCompletionTime = now;
        if (stage.kind() != StageKind.WORKING) {
            long held = now - job.startTime;
            switch (stage.kind()) {
                case PARKING -> {
                    state.parkingSeconds += held;
                    state.batchParkedSeconds.merge(job.batch.id(), held, Long::sum);
                }
                case WAITING -> state.waitingStageSeconds += held;
                case JOB_WORK -> state.jobWorkSeconds += held;
                default -> throw new IllegalStateException("Unknown stage kind " + stage.kind());
            }
        }
        state.trace(now, TraceType.JOB_COMPLETED, job.batch, stage.id(), null, outputStones, outputCarats, null);

        int next = job.batch.nextStageAfter(job.stageIndex);
        if (next < 0) {
            if (state.batchFinishedAt.put(job.batch.id(), now) != null) {
                throw new IllegalStateException("Batch " + job.batch.id() + " finished twice");
            }
            state.finishedStones = state.finishedStones.add(outputStones);
            state.finishedCarats = state.finishedCarats.add(outputCarats);
            UUID orderId = job.batch.orderId();
            if (context.batchIdsByOrder.get(orderId).stream().allMatch(state.batchFinishedAt::containsKey)) {
                state.orderCompletedAt.put(orderId, now);
                state.trace(now, TraceType.ORDER_COMPLETED, job.batch, null, null, null, null, null);
            }
        } else {
            enqueue(new ProductionJob(job.batch, next, outputStones, outputCarats, now), context, state, events, now);
        }
    }

    private static void enqueue(ProductionJob job, SimulationContext context, SimulationState state,
            SimulationEventQueue events, long now) {
        if (context.stage(job.stageIndex).kind() != StageKind.WORKING) {
            hold(job, context, state, events, now);
            return;
        }
        if (!state.queues.get(job.stageIndex).add(job)) {
            throw new IllegalStateException("Batch " + job.batch.id() + " is already queued at that stage");
        }
        state.trace(now, TraceType.JOB_QUEUED, job.batch, context.stage(job.stageIndex).id(), null,
                job.inputStones, job.inputCarats, null);
    }

    /**
     * Holds a batch at a parking, waiting or job-work stage for its measured delay, counted from when it
     * entered the stage (which may be before the fork) and never ending before now. No capacity is used
     * and no queue is joined; the time held is recorded by kind when it completes.
     */
    private static void hold(ProductionJob job, SimulationContext context, SimulationState state,
            SimulationEventQueue events, long now) {
        StageSpec stage = context.stage(job.stageIndex);
        long entered = job.queueEntryTime;
        job.startTime = Math.max(entered, context.forkTime);
        job.workSeconds = 0;
        job.completionTime = Math.max(now, entered + context.delayFor(job.batch.id(), stage));
        state.running.put(job.batch.id(), job);
        state.trace(now, TraceType.JOB_STARTED, job.batch, stage.id(), null, job.inputStones, job.inputCarats, null);
        events.schedule(job.completionTime, SimulationEvent.Type.STAGE_COMPLETED, job.batch.id());
    }

    /** At most one dispatch per instant, after everything else that happens then. */
    private static void requestDispatch(SimulationState state, SimulationEventQueue events, long now) {
        if (!state.dispatchPending) {
            state.dispatchPending = true;
            events.schedule(now, SimulationEvent.Type.DISPATCH_ATTEMPT, null);
        }
    }

    /**
     * While work remains, wake up at the start of each working interval, so that jobs that became
     * ready while the factory was closed are started when it opens. When nothing remains, nothing
     * is scheduled and the run ends.
     */
    private static void scheduleNextShiftStart(SimulationContext context, SimulationState state,
            SimulationEventQueue events, long now) {
        if (!state.hasUnfinishedWork()) {
            return;
        }
        long next = context.calendar.nextIntervalStartAfter(now, context.horizonEnd);
        // Beyond the horizon it is still scheduled, so that stuck work ends as HORIZON_REACHED.
        events.schedule(next, SimulationEvent.Type.SHIFT_START, null);
    }

    private static BigDecimal scaled(BigDecimal quantity) {
        return quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN);
    }
}
