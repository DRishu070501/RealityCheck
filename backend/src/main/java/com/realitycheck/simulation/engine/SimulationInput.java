package com.realitycheck.simulation.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything a simulation run depends on. The engine reads nothing else: no database, no clock,
 * no configuration. Times are epoch seconds (UTC); durations are whole seconds; quantities and
 * money are {@link BigDecimal}. List order never matters: the engine sorts every list by a stable
 * key before use.
 *
 * @param forkTime the instant the simulation starts from (T0)
 * @param batches  every batch that still has work to do at the fork, or arrives after it
 * @param routes   the paths batches may follow; a batch without a route visits every stage in
 *                 sequence order, which is the only behaviour when this list is empty
 */
public record SimulationInput(
        long forkTime,
        CalendarSpec calendar,
        List<StageSpec> stages,
        List<PoolSpec> pools,
        List<OrderSpec> orders,
        List<BatchSpec> batches,
        SimulationLimits limits,
        BottleneckConfig bottleneck,
        List<RouteSpec> routes) {

    public SimulationInput {
        stages = List.copyOf(stages);
        pools = List.copyOf(pools);
        orders = List.copyOf(orders);
        batches = List.copyOf(batches);
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    /** An input without routes: every batch visits every stage in sequence order. */
    public SimulationInput(long forkTime, CalendarSpec calendar, List<StageSpec> stages, List<PoolSpec> pools,
            List<OrderSpec> orders, List<BatchSpec> batches, SimulationLimits limits, BottleneckConfig bottleneck) {
        this(forkTime, calendar, stages, pools, orders, batches, limits, bottleneck, List.of());
    }

    /**
     * A path through the workflow, such as the one a packet type follows. Steps are in the order
     * they are visited and each stage appears at most once.
     */
    public record RouteSpec(String code, List<RouteStep> steps) {

        public RouteSpec {
            steps = List.copyOf(steps);
        }
    }

    /**
     * @param stageSequence the stage visited
     * @param visitPercent  1 to 100: the share of batches on the route that visit this stage. Which
     *                      batches do is decided by a fixed hash of the batch id and the stage, so
     *                      the same input always takes the same paths.
     */
    public record RouteStep(int stageSequence, int visitPercent) {
    }

    /** Weekly working pattern in a time zone. Days without a shift are closed. */
    public record CalendarSpec(String zoneId, List<ShiftSpec> shifts) {

        public CalendarSpec {
            shifts = List.copyOf(shifts);
        }
    }

    /**
     * @param dayOfWeek        ISO: 1 = Monday .. 7 = Sunday
     * @param startSecondOfDay local time as seconds since local midnight
     * @param endSecondOfDay   exclusive; 86400 means the following local midnight
     */
    public record ShiftSpec(int dayOfWeek, int startSecondOfDay, int endSecondOfDay, List<BreakSpec> breaks) {

        public ShiftSpec {
            breaks = List.copyOf(breaks);
        }
    }

    public record BreakSpec(int startSecondOfDay, int endSecondOfDay) {
    }

    /**
     * @param sequence            position in the workflow; sequences are exactly 1..N
     * @param stonesPerHourPerSlot processing rate of one slot
     * @param setupSeconds        added once per job
     * @param stoneYield          fraction of stones that leaves the stage, in (0, 1]
     * @param requirements        what one processing slot needs; empty means unlimited slots
     * @param kind                how the stage holds a batch; see {@link StageKind}
     * @param delayDeciles        for every kind but WORKING: ten calendar durations in seconds, ascending,
     *                            one of which each batch spends here (chosen by a fixed hash)
     * @param handlingDeciles     WORKING only, optional: ten calendar durations in seconds, ascending, one of
     *                            which each batch stays after processing, with its units already released
     */
    public record StageSpec(UUID id, String code, int sequence, BigDecimal stonesPerHourPerSlot, long setupSeconds,
            BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
            List<RequirementSpec> requirements, StageKind kind, List<Long> delayDeciles, List<Long> handlingDeciles) {

        public StageSpec {
            requirements = List.copyOf(requirements);
            kind = kind == null ? StageKind.WORKING : kind;
            delayDeciles = delayDeciles == null ? List.of() : List.copyOf(delayDeciles);
            handlingDeciles = handlingDeciles == null ? List.of() : List.copyOf(handlingDeciles);
        }

        /** A stage without handling delays. */
        public StageSpec(UUID id, String code, int sequence, BigDecimal stonesPerHourPerSlot, long setupSeconds,
                BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
                List<RequirementSpec> requirements, StageKind kind, List<Long> delayDeciles) {
            this(id, code, sequence, stonesPerHourPerSlot, setupSeconds, stoneYield, caratYield, fixedCostPerJob,
                    requirements, kind, delayDeciles, List.of());
        }

        /** A working stage: processing on slots of pool units, as before stage kinds existed. */
        public StageSpec(UUID id, String code, int sequence, BigDecimal stonesPerHourPerSlot, long setupSeconds,
                BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
                List<RequirementSpec> requirements) {
            this(id, code, sequence, stonesPerHourPerSlot, setupSeconds, stoneYield, caratYield, fixedCostPerJob,
                    requirements, StageKind.WORKING, List.of());
        }
    }

    /**
     * How a stage holds a batch. Only WORKING uses capacity; the others hold a batch for a delay drawn
     * from the stage's measured durations, with no limit on how many batches they hold at once.
     */
    public enum StageKind {
        /** Setup and processing on slots of pool units. */
        WORKING,
        /** Material deliberately parked until it is wanted; excluded from manufacturing lead time. */
        PARKING,
        /** Waiting that is part of manufacturing, such as waiting for dispatch. */
        WAITING,
        /** Sent to an outside job worker; the factory's capacity is not used. */
        JOB_WORK
    }

    public record RequirementSpec(UUID poolId, int unitsPerSlot) {
    }

    public enum ResourceKind {
        MACHINE,
        EMPLOYEE
    }

    /**
     * @param baseCapacity units in the pool before windows
     * @param hourlyCost   per unit per hour
     */
    public record PoolSpec(UUID id, ResourceKind kind, String name, int baseCapacity, BigDecimal hourlyCost,
            List<CapacityWindow> windows) {

        public PoolSpec {
            windows = List.copyOf(windows);
        }
    }

    /** A change of {@code delta} units over the half-open period {@code [from, to)}. */
    public record CapacityWindow(long from, long to, int delta) {
    }

    /**
     * @param priority higher is served first
     * @param dueTime  null when the order has no due date
     */
    public record OrderSpec(UUID id, String orderNumber, int priority, Long dueTime) {
    }

    public enum BatchEntryState {
        /** Waiting at {@code stageSequence} at the fork. */
        QUEUED,
        /** Being worked on at {@code stageSequence} at the fork, already holding its units. */
        IN_PROGRESS,
        /** Not yet in the factory; joins the queue of {@code stageSequence} at {@code arrivalTime}. */
        FUTURE_ARRIVAL
    }

    /**
     * @param stones         quantity entering {@code stageSequence}
     * @param queueEntryTime when it joined the queue (QUEUED); may be before the fork
     * @param startTime      when work began (IN_PROGRESS); at or before the fork
     * @param arrivalTime    when it arrives (FUTURE_ARRIVAL)
     * @param routeCode      the route it follows; null for every stage in sequence order
     */
    public record BatchSpec(UUID id, UUID orderId, BatchEntryState state, int stageSequence, BigDecimal stones,
            BigDecimal carats, Long queueEntryTime, Long startTime, Long arrivalTime, String routeCode) {

        /** A batch that visits every stage in sequence order. */
        public BatchSpec(UUID id, UUID orderId, BatchEntryState state, int stageSequence, BigDecimal stones,
                BigDecimal carats, Long queueEntryTime, Long startTime, Long arrivalTime) {
            this(id, orderId, state, stageSequence, stones, carats, queueEntryTime, startTime, arrivalTime, null);
        }
    }

    /** The run stops at whichever comes first. Reaching a limit is a result, not an error. */
    public record SimulationLimits(long maxSimulatedSeconds, long maxEvents) {

        /** 180 days and one million events. */
        public static SimulationLimits defaults() {
            return new SimulationLimits(180L * 24 * 3600, 1_000_000);
        }
    }

    /**
     * Weights of the four bottleneck signals and the two thresholds a primary bottleneck must reach.
     *
     * @param scoreThreshold      the weighted score a stage needs
     * @param saturationThreshold the share of the stage's active time that must have been spent
     *                            with work waiting behind full slots
     */
    public record BottleneckConfig(BigDecimal utilizationWeight, BigDecimal waitWeight, BigDecimal queueWeight,
            BigDecimal saturationWeight, BigDecimal scoreThreshold, BigDecimal saturationThreshold) {

        public static BottleneckConfig defaults() {
            return new BottleneckConfig(new BigDecimal("0.35"), new BigDecimal("0.30"), new BigDecimal("0.20"),
                    new BigDecimal("0.15"), new BigDecimal("0.50"), new BigDecimal("0.50"));
        }
    }
}
