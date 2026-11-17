package com.realitycheck.simulation.engine;

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
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import com.realitycheck.simulation.engine.SimulationInput.SimulationLimits;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds simulation inputs for tests with readable calls and predictable ids. */
final class InputBuilder {

    /** Monday 5 January 2026, 09:00 in Asia/Kolkata: the start of a working week. */
    static final long MONDAY_9AM = Instant.parse("2026-01-05T03:30:00Z").getEpochSecond();
    static final long HOUR = 3_600;
    static final long DAY = 86_400;

    private long forkTime;
    private CalendarSpec calendar;
    private SimulationLimits limits = SimulationLimits.defaults();
    private BottleneckConfig bottleneck = BottleneckConfig.defaults();
    private final Map<UUID, PoolSpec> pools = new LinkedHashMap<>();
    private final List<StageSpec> stages = new ArrayList<>();
    private final List<OrderSpec> orders = new ArrayList<>();
    private final List<BatchSpec> batches = new ArrayList<>();
    private final List<SimulationInput.RouteSpec> routes = new ArrayList<>();
    private final Map<UUID, String> routeOf = new LinkedHashMap<>();
    private long nextId = 1;

    private InputBuilder(long forkTime, CalendarSpec calendar) {
        this.forkTime = forkTime;
        this.calendar = calendar;
    }

    /** Open every second of every day, in UTC, starting at time zero: working time equals elapsed time. */
    static InputBuilder alwaysOpen() {
        List<ShiftSpec> shifts = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            shifts.add(new ShiftSpec(day, 0, 86_400, List.of()));
        }
        return new InputBuilder(0, new CalendarSpec("UTC", shifts));
    }

    /** Monday to Saturday 09:00 to 18:00 with a 13:00 to 14:00 break, Asia/Kolkata, forking Monday 09:00. */
    static InputBuilder demoCalendar() {
        List<ShiftSpec> shifts = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            shifts.add(new ShiftSpec(day, 9 * 3600, 18 * 3600, List.of(new BreakSpec(13 * 3600, 14 * 3600))));
        }
        return new InputBuilder(MONDAY_9AM, new CalendarSpec("Asia/Kolkata", shifts));
    }

    InputBuilder forkAt(long time) {
        this.forkTime = time;
        return this;
    }

    InputBuilder calendar(CalendarSpec spec) {
        this.calendar = spec;
        return this;
    }

    InputBuilder limits(long maxSimulatedSeconds, long maxEvents) {
        this.limits = new SimulationLimits(maxSimulatedSeconds, maxEvents);
        return this;
    }

    InputBuilder bottleneck(BottleneckConfig config) {
        this.bottleneck = config;
        return this;
    }

    long forkTime() {
        return forkTime;
    }

    UUID machines(String name, int capacity, String hourlyCost) {
        return pool(name, ResourceKind.MACHINE, capacity, hourlyCost);
    }

    UUID employees(String name, int capacity, String hourlyCost) {
        return pool(name, ResourceKind.EMPLOYEE, capacity, hourlyCost);
    }

    private UUID pool(String name, ResourceKind kind, int capacity, String hourlyCost) {
        UUID id = id(1);
        pools.put(id, new PoolSpec(id, kind, name, capacity, new BigDecimal(hourlyCost), List.of()));
        return id;
    }

    /** Changes a pool's capacity by {@code delta} over {@code [from, to)}. */
    InputBuilder window(UUID poolId, long from, long to, int delta) {
        PoolSpec pool = pools.get(poolId);
        List<CapacityWindow> windows = new ArrayList<>(pool.windows());
        windows.add(new CapacityWindow(from, to, delta));
        pools.put(poolId, new PoolSpec(pool.id(), pool.kind(), pool.name(), pool.baseCapacity(), pool.hourlyCost(),
                windows));
        return this;
    }

    static RequirementSpec needs(UUID poolId, int units) {
        return new RequirementSpec(poolId, units);
    }

    /** Adds the next stage: no yield loss, no fixed cost. */
    UUID stage(String code, String stonesPerHour, long setupSeconds, RequirementSpec... requirements) {
        return stage(code, stonesPerHour, setupSeconds, "1", "1", "0", requirements);
    }

    UUID stage(String code, String stonesPerHour, long setupSeconds, String stoneYield, String caratYield,
            String fixedCost, RequirementSpec... requirements) {
        UUID id = id(2);
        stages.add(new StageSpec(id, code, stages.size() + 1, new BigDecimal(stonesPerHour), setupSeconds,
                new BigDecimal(stoneYield), new BigDecimal(caratYield), new BigDecimal(fixedCost),
                List.of(requirements)));
        return id;
    }

    UUID order(String number) {
        return order(number, 3, null);
    }

    UUID order(String number, int priority, Long dueTime) {
        UUID id = id(3);
        orders.add(new OrderSpec(id, number, priority, dueTime));
        return id;
    }

    /** A batch waiting at a stage since the fork. Carats are 1.3 times the stones unless given. */
    UUID queued(UUID orderId, int stageSequence, String stones) {
        return queuedSince(orderId, stageSequence, stones, forkTime);
    }

    UUID queuedSince(UUID orderId, int stageSequence, String stones, long queueEntryTime) {
        return batch(orderId, BatchEntryState.QUEUED, stageSequence, stones, queueEntryTime, null, null);
    }

    UUID inProgress(UUID orderId, int stageSequence, String stones, long startTime) {
        return batch(orderId, BatchEntryState.IN_PROGRESS, stageSequence, stones, null, startTime, null);
    }

    UUID arriving(UUID orderId, int stageSequence, String stones, long arrivalTime) {
        return batch(orderId, BatchEntryState.FUTURE_ARRIVAL, stageSequence, stones, null, null, arrivalTime);
    }

    /** One order holding one batch queued at the first stage. Returns the batch id. */
    UUID job(String orderNumber, String stones) {
        return queued(order(orderNumber), 1, stones);
    }

    private UUID batch(UUID orderId, BatchEntryState state, int stageSequence, String stones, Long queueEntryTime,
            Long startTime, Long arrivalTime) {
        UUID id = id(4);
        BigDecimal stoneCount = new BigDecimal(stones);
        batches.add(new BatchSpec(id, orderId, state, stageSequence, stoneCount,
                stoneCount.multiply(new BigDecimal("1.3")), queueEntryTime, startTime, arrivalTime));
        return id;
    }

    /** @param steps pairs of stage sequence and visit percent */
    InputBuilder route(String code, int... steps) {
        List<SimulationInput.RouteStep> list = new ArrayList<>();
        for (int i = 0; i < steps.length; i += 2) {
            list.add(new SimulationInput.RouteStep(steps[i], steps[i + 1]));
        }
        routes.add(new SimulationInput.RouteSpec(code, list));
        return this;
    }

    InputBuilder onRoute(UUID batchId, String code) {
        routeOf.put(batchId, code);
        return this;
    }

    SimulationInput build() {
        List<BatchSpec> withRoutes = batches.stream()
                .map(b -> new BatchSpec(b.id(), b.orderId(), b.state(), b.stageSequence(), b.stones(), b.carats(),
                        b.queueEntryTime(), b.startTime(), b.arrivalTime(), routeOf.get(b.id())))
                .toList();
        return new SimulationInput(forkTime, calendar, stages, List.copyOf(pools.values()), orders, withRoutes,
                limits, bottleneck, routes);
    }

    SimulationResult run() {
        return new SimulationEngine().run(build());
    }

    /** Ids ascend in creation order within each kind, so "lowest id" tie-breaks are predictable. */
    private UUID id(long kind) {
        return new UUID(kind, nextId++);
    }
}
