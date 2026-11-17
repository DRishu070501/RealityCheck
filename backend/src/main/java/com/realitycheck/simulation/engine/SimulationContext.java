package com.realitycheck.simulation.engine;

import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.CapacityWindow;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteStep;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Read-only lookups derived once from a validated input. Every collection is put into a defined
 * order here (stages by sequence, everything else by id), which is what makes the result
 * independent of the order the input lists arrived in.
 */
final class SimulationContext {

    final SimulationInput input;
    final long forkTime;
    final long horizonEnd;
    /** Stages in sequence order; index = sequence - 1. Each stage's requirements are in pool-id order. */
    final List<StageSpec> stages;
    final TreeMap<UUID, PoolSpec> pools = new TreeMap<>();
    final TreeMap<UUID, CapacityProfile> capacityProfiles = new TreeMap<>();
    final TreeMap<UUID, OrderSpec> orders = new TreeMap<>();
    /** Batches in id order. */
    final List<BatchSpec> batches;
    final TreeMap<UUID, List<UUID>> batchIdsByOrder = new TreeMap<>();
    final TreeMap<String, RouteSpec> routes = new TreeMap<>();
    /** The factory calendar. */
    final WorkingCalendar calendar;
    /**
     * Per stage: the calendar minus the periods in which any pool the stage needs has zero
     * capacity. A job at the stage makes progress only during this time.
     */
    final List<WorkingCalendar> stageWorkingTime;

    SimulationContext(SimulationInput input) {
        this.input = input;
        this.forkTime = input.forkTime();
        this.horizonEnd = forkTime + input.limits().maxSimulatedSeconds();
        this.calendar = new WorkingCalendar(input.calendar());

        for (PoolSpec pool : input.pools()) {
            List<CapacityWindow> windows = pool.windows().stream()
                    .sorted(Comparator.comparingLong(CapacityWindow::from)
                            .thenComparingLong(CapacityWindow::to)
                            .thenComparingInt(CapacityWindow::delta))
                    .toList();
            pools.put(pool.id(), pool);
            capacityProfiles.put(pool.id(), new CapacityProfile(pool.baseCapacity(), windows));
        }

        List<StageSpec> ordered = new ArrayList<>();
        List<WorkingCalendar> workingTime = new ArrayList<>();
        for (StageSpec stage : input.stages().stream().sorted(Comparator.comparingInt(StageSpec::sequence)).toList()) {
            List<RequirementSpec> requirements = stage.requirements().stream()
                    .sorted(Comparator.comparing(RequirementSpec::poolId))
                    .toList();
            ordered.add(new StageSpec(stage.id(), stage.code(), stage.sequence(), stage.stonesPerHourPerSlot(),
                    stage.setupSeconds(), stage.stoneYield(), stage.caratYield(), stage.fixedCostPerJob(),
                    requirements));
            IntervalSet unavailable = IntervalSet.EMPTY;
            for (RequirementSpec requirement : requirements) {
                // Work in progress at the fork may have started before it, so look back as well.
                unavailable = unavailable.union(capacityProfiles.get(requirement.poolId())
                        .zeroIntervals(earliestStart(input), horizonEnd));
            }
            workingTime.add(calendar.without(unavailable));
        }
        this.stages = List.copyOf(ordered);
        this.stageWorkingTime = List.copyOf(workingTime);

        for (OrderSpec order : input.orders()) {
            orders.put(order.id(), order);
            batchIdsByOrder.put(order.id(), new ArrayList<>());
        }
        for (RouteSpec route : input.routes()) {
            routes.put(route.code(), route);
        }
        this.batches = input.batches().stream().sorted(Comparator.comparing(BatchSpec::id)).toList();
        for (BatchSpec batch : batches) {
            batchIdsByOrder.get(batch.orderId()).add(batch.id());
        }
    }

    StageSpec stage(int index) {
        return stages.get(index);
    }

    /**
     * The zero-based stage indexes a batch visits, in order. Without a route: every stage in
     * sequence order. With one: the route's steps, keeping a step below 100% only for the batches
     * {@link #visits} selects, and always keeping the stage the batch is at when the run starts.
     */
    List<Integer> pathFor(BatchSpec batch) {
        List<Integer> path = new ArrayList<>();
        if (batch.routeCode() == null) {
            for (int i = 0; i < stages.size(); i++) {
                path.add(i);
            }
            return path;
        }
        for (RouteStep step : routes.get(batch.routeCode()).steps()) {
            if (step.stageSequence() == batch.stageSequence() || visits(batch.id(), step)) {
                path.add(step.stageSequence() - 1);
            }
        }
        return path;
    }

    /**
     * Whether a batch visits an optional step. A fixed mix of the batch id and the stage gives each
     * pair a bucket from 0 to 99; the step is visited when the bucket is below its percentage. No
     * randomness: the same pair always gets the same answer.
     */
    static boolean visits(UUID batchId, RouteStep step) {
        if (step.visitPercent() >= 100) {
            return true;
        }
        long x = batchId.getMostSignificantBits() ^ Long.rotateLeft(batchId.getLeastSignificantBits(), 17)
                ^ (step.stageSequence() * 0x9E3779B97F4A7C15L);
        // SplitMix64 finaliser: spreads every input bit over the result.
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x = x ^ (x >>> 31);
        return Math.floorMod(x, 100) < step.visitPercent();
    }

    private static long earliestStart(SimulationInput input) {
        long earliest = input.forkTime();
        for (BatchSpec batch : input.batches()) {
            if (batch.startTime() != null) {
                earliest = Math.min(earliest, batch.startTime());
            }
        }
        return earliest;
    }
}
