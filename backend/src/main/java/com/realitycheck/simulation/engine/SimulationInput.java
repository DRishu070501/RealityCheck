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
 */
public record SimulationInput(
        long forkTime,
        CalendarSpec calendar,
        List<StageSpec> stages,
        List<PoolSpec> pools,
        List<OrderSpec> orders,
        List<BatchSpec> batches,
        SimulationLimits limits,
        BottleneckConfig bottleneck) {

    public SimulationInput {
        stages = List.copyOf(stages);
        pools = List.copyOf(pools);
        orders = List.copyOf(orders);
        batches = List.copyOf(batches);
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
     */
    public record StageSpec(UUID id, String code, int sequence, BigDecimal stonesPerHourPerSlot, long setupSeconds,
            BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
            List<RequirementSpec> requirements) {

        public StageSpec {
            requirements = List.copyOf(requirements);
        }
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
     */
    public record BatchSpec(UUID id, UUID orderId, BatchEntryState state, int stageSequence, BigDecimal stones,
            BigDecimal carats, Long queueEntryTime, Long startTime, Long arrivalTime) {
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
