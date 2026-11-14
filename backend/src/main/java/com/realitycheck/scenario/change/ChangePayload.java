package com.realitycheck.scenario.change;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The typed body of a scenario change, one record per {@link ChangeType}. Field rules are checked
 * by {@link ChangePayloads#validate}; how each is applied is {@link ChangeApplier}'s concern.
 */
public sealed interface ChangePayload {

    ChangeType type();

    enum CapacityMode {
        /** Set the pool's capacity to {@code value} units. */
        ABSOLUTE,
        /** Add {@code value} units (negative removes); never below zero. */
        DELTA,
        /** Change by {@code value} percent, rounded down to whole units; never below zero. */
        PERCENT
    }

    enum RateMode {
        ABSOLUTE,
        PERCENT
    }

    enum ScheduleMode {
        /** Add the shifts to the factory's calendar. */
        ADD,
        /** Use only the given shifts. */
        REPLACE
    }

    /** Sets a pool's base capacity. */
    record ResourceCapacity(UUID resourceId, CapacityMode mode, BigDecimal value) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.RESOURCE_CAPACITY;
        }
    }

    /**
     * Takes {@code units} out of a pool for a period: from {@code from} (the fork when absent) to
     * {@code to} (the end of the simulation horizon when absent).
     */
    record ResourceUnavailable(UUID resourceId, Integer units, Instant from, Instant to) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.RESOURCE_UNAVAILABLE;
        }
    }

    /** Takes every unit of a pool out for a period. */
    record ResourceDowntime(UUID resourceId, Instant from, Instant to) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.RESOURCE_DOWNTIME;
        }
    }

    /** Scales the stones and carats of every batch that has not started its first stage at the fork. */
    record DemandVolume(BigDecimal percent) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.DEMAND_VOLUME;
        }
    }

    /** Sets a stage's processing rate per slot. */
    record ProcessingRate(UUID stageId, RateMode mode, BigDecimal value) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.PROCESSING_RATE;
        }
    }

    /** Adds shifts to the calendar or replaces it. Shifts apply to the whole factory. */
    record WorkingSchedule(ScheduleMode mode, List<Shift> shifts) implements ChangePayload {

        @Override
        public ChangeType type() {
            return ChangeType.WORKING_SCHEDULE;
        }
    }

    /** {@code dayOfWeek} is ISO: 1 = Monday .. 7 = Sunday. */
    record Shift(Integer dayOfWeek, @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") LocalTime endTime, List<Break> breaks) {

        public List<Break> breaksOrEmpty() {
            return breaks == null ? List.of() : breaks;
        }
    }

    record Break(@JsonFormat(pattern = "HH:mm") LocalTime startTime, @JsonFormat(pattern = "HH:mm") LocalTime endTime) {
    }
}
