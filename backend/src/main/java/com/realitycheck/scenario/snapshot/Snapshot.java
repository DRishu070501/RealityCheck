package com.realitycheck.scenario.snapshot;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything a simulation run is computed from: the factory as it stood at the fork, with a
 * scenario's changes applied. A run stores its snapshot and reads nothing else, so its results can
 * be reproduced and are unaffected by later edits.
 *
 * <p>Plain, Jackson-serialisable records with no entity references. Every list is sorted by a
 * stable key (noted on each), so the same data always serialises to the same bytes and hashes to
 * the same value.
 *
 * @param pools          active pools only, sorted by id
 * @param orders         orders with work left at the fork or arriving after it, sorted by id
 * @param batches        sorted by id
 * @param appliedChanges in ordinal order; empty in a base snapshot
 */
public record Snapshot(
        int schemaVersion,
        UUID factoryId,
        String timeZone,
        String currencyCode,
        Instant forkAt,
        CalendarSnapshot calendar,
        List<StageSnapshot> stages,
        List<PoolSnapshot> pools,
        List<OrderSnapshot> orders,
        List<BatchSnapshot> batches,
        LimitsSnapshot limits,
        BottleneckConfigSnapshot bottleneck,
        List<AppliedChange> appliedChanges) {

    public static final int SCHEMA_VERSION = 1;

    public Snapshot {
        stages = List.copyOf(stages);
        pools = List.copyOf(pools);
        orders = List.copyOf(orders);
        batches = List.copyOf(batches);
        appliedChanges = List.copyOf(appliedChanges);
    }

    /** Shifts sorted by day, then start. */
    public record CalendarSnapshot(List<ShiftSnapshot> shifts) {

        public CalendarSnapshot {
            shifts = List.copyOf(shifts);
        }
    }

    /**
     * @param dayOfWeek ISO: 1 = Monday .. 7 = Sunday
     * @param breaks    sorted by start
     */
    public record ShiftSnapshot(
            int dayOfWeek,
            @JsonFormat(pattern = "HH:mm:ss") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime,
            List<BreakSnapshot> breaks) {

        public ShiftSnapshot {
            breaks = List.copyOf(breaks);
        }
    }

    public record BreakSnapshot(
            @JsonFormat(pattern = "HH:mm:ss") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime) {
    }

    /**
     * @param stonesPerHour per processing slot
     * @param requirements  sorted by pool id
     */
    public record StageSnapshot(UUID id, String code, String name, int sequence, BigDecimal stonesPerHour,
            long setupSeconds, BigDecimal stoneYield, BigDecimal caratYield, BigDecimal fixedCostPerJob,
            List<RequirementSnapshot> requirements) {

        public StageSnapshot {
            requirements = List.copyOf(requirements);
        }
    }

    public record RequirementSnapshot(UUID poolId, int unitsPerSlot) {
    }

    /**
     * @param kind       {@code MACHINE} or {@code EMPLOYEE}
     * @param hourlyCost per unit per hour
     * @param windows    dated capacity changes, sorted by from, to, delta
     */
    public record PoolSnapshot(UUID id, String kind, String category, String name, int baseCapacity,
            BigDecimal hourlyCost, List<CapacityWindowSnapshot> windows) {

        public PoolSnapshot {
            windows = List.copyOf(windows);
        }
    }

    /** A change of {@code delta} units over the half-open period {@code [from, to)}. */
    public record CapacityWindowSnapshot(Instant from, Instant to, int delta) {
    }

    /** @param dueAt null when the order has no due date */
    public record OrderSnapshot(UUID id, String orderNumber, int priority, Instant dueAt, Instant placedAt) {
    }

    /**
     * @param state         {@code QUEUED}, {@code IN_PROGRESS} or {@code FUTURE_ARRIVAL}
     * @param stageSequence the stage it is queued at, in progress at, or arrives at
     * @param stones        what it holds on entering that stage; not rounded to whole stones
     * @param queuedAt      when it joined the queue (QUEUED)
     * @param startedAt     when work began (IN_PROGRESS)
     * @param arrivalAt     when it arrives (FUTURE_ARRIVAL)
     */
    public record BatchSnapshot(UUID id, UUID orderId, int batchNumber, String state, int stageSequence,
            BigDecimal stones, BigDecimal carats, Instant queuedAt, Instant startedAt, Instant arrivalAt) {

        public static final String QUEUED = "QUEUED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String FUTURE_ARRIVAL = "FUTURE_ARRIVAL";

        public BatchSnapshot withQuantities(BigDecimal newStones, BigDecimal newCarats) {
            return new BatchSnapshot(id, orderId, batchNumber, state, stageSequence, newStones, newCarats, queuedAt,
                    startedAt, arrivalAt);
        }
    }

    public record LimitsSnapshot(long maxSimulatedSeconds, long maxEvents) {
    }

    public record BottleneckConfigSnapshot(BigDecimal utilizationWeight, BigDecimal waitWeight,
            BigDecimal queueWeight, BigDecimal saturationWeight, BigDecimal scoreThreshold,
            BigDecimal saturationThreshold) {
    }

    /**
     * A scenario change as it was applied, so a run shows exactly what was changed even if the
     * scenario is edited later.
     *
     * @param summary plain-language description built from the payload and names; no simulated numbers
     */
    public record AppliedChange(int ordinal, String changeType, JsonNode payload, String summary) {
    }
}
