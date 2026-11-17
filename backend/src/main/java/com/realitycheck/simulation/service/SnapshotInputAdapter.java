package com.realitycheck.simulation.service;

import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import com.realitycheck.simulation.engine.SimulationInput;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.CapacityWindow;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.ResourceKind;
import com.realitycheck.simulation.engine.SimulationInput.RouteSpec;
import com.realitycheck.simulation.engine.SimulationInput.RouteStep;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import com.realitycheck.simulation.engine.SimulationInput.SimulationLimits;
import com.realitycheck.simulation.engine.SimulationInput.StageKind;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps a snapshot (spec 06) to the engine's input. Instants become epoch seconds and local times
 * seconds of day; nothing else happens here.
 */
public final class SnapshotInputAdapter {

    private SnapshotInputAdapter() {
    }

    public static SimulationInput toInput(Snapshot s) {
        CalendarSpec calendar = new CalendarSpec(s.timeZone(), s.calendar().shifts().stream()
                .map(shift -> new ShiftSpec(shift.dayOfWeek(), shift.startTime().toSecondOfDay(),
                        shift.endTime().toSecondOfDay(),
                        shift.breaks().stream()
                                .map(b -> new BreakSpec(b.startTime().toSecondOfDay(), b.endTime().toSecondOfDay()))
                                .toList()))
                .toList());
        return new SimulationInput(
                s.forkAt().getEpochSecond(),
                calendar,
                s.stages().stream().map(SnapshotInputAdapter::toStage).toList(),
                s.pools().stream().map(SnapshotInputAdapter::toPool).toList(),
                s.orders().stream()
                        .map(o -> new OrderSpec(o.id(), o.orderNumber(), o.priority(), epoch(o.dueAt())))
                        .toList(),
                s.batches().stream().map(SnapshotInputAdapter::toBatch).toList(),
                new SimulationLimits(s.limits().maxSimulatedSeconds(), s.limits().maxEvents()),
                new BottleneckConfig(s.bottleneck().utilizationWeight(), s.bottleneck().waitWeight(),
                        s.bottleneck().queueWeight(), s.bottleneck().saturationWeight(),
                        s.bottleneck().scoreThreshold(), s.bottleneck().saturationThreshold()),
                s.routes().stream()
                        .map(r -> new RouteSpec(r.code(), r.steps().stream()
                                .map(step -> new RouteStep(step.stageSequence(), step.visitPercent())).toList()))
                        .toList());
    }

    /** Stage display names by id, copied into results so they stay readable after configuration edits. */
    public static Map<UUID, String> stageNames(Snapshot s) {
        return s.stages().stream().collect(Collectors.toMap(StageSnapshot::id, StageSnapshot::name));
    }

    /** Each pool's base capacity in the snapshot, after the scenario's changes. */
    public static Map<UUID, Integer> poolCapacities(Snapshot s) {
        return s.pools().stream().collect(Collectors.toMap(PoolSnapshot::id, PoolSnapshot::baseCapacity));
    }

    private static StageSpec toStage(StageSnapshot stage) {
        return new StageSpec(stage.id(), stage.code(), stage.sequence(), stage.stonesPerHour(), stage.setupSeconds(),
                stage.stoneYield(), stage.caratYield(), stage.fixedCostPerJob(),
                stage.requirements().stream().map(r -> new RequirementSpec(r.poolId(), r.unitsPerSlot())).toList(),
                stage.kind() == null ? StageKind.WORKING : StageKind.valueOf(stage.kind()), stage.delayDeciles(),
                stage.handlingDeciles());
    }

    private static PoolSpec toPool(PoolSnapshot pool) {
        return new PoolSpec(pool.id(), ResourceKind.valueOf(pool.kind()), pool.name(), pool.baseCapacity(),
                pool.hourlyCost(), pool.windows().stream()
                        .map(w -> new CapacityWindow(w.from().getEpochSecond(), w.to().getEpochSecond(), w.delta()))
                        .toList());
    }

    private static BatchSpec toBatch(BatchSnapshot batch) {
        BatchEntryState state = BatchEntryState.valueOf(batch.state());
        return new BatchSpec(batch.id(), batch.orderId(), state, batch.stageSequence(), batch.stones(), batch.carats(),
                epoch(batch.queuedAt()), epoch(batch.startedAt()), epoch(batch.arrivalAt()), batch.routeCode());
    }

    private static Long epoch(Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }
}
