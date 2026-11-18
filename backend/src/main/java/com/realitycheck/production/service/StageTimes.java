package com.realitycheck.production.service;

import com.realitycheck.production.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where packets' time went, from recorded history (spec 11, tasks 12 and 13). A pure function over each
 * batch's effective events (corrections applied).
 *
 * <p>For every visit to a stage, the calendar time from being queued to being started is <em>waiting</em>,
 * and from being started to being completed is <em>active</em> (worked, at a working stage; held, at a
 * parking, waiting or job-work stage). A visit still open at {@code until} counts up to then. A completed
 * visit also records what entered and left the stage, for yields. A batch whose last event is a completion
 * with no queueing after it has finished its journey: its lead time is from its first queueing to that
 * completion, and its parking time is the time it spent at parking stages. Nothing is estimated.
 */
public final class StageTimes {

    private StageTimes() {
    }

    /** One batch: its route, what it started with, and its effective events in order. */
    public record BatchRecord(String routeCode, BigDecimal stones, BigDecimal carats, List<BatchHistory.Event> events) {
    }

    /**
     * Summed over every visit to one stage.
     *
     * @param completedVisits visits that finished at the stage within the window
     * @param stonesIn        entering the stage, over completed visits only, so that yields compare like with like
     */
    public record Totals(long visits, long waitingSeconds, long activeSeconds, long completedVisits,
            BigDecimal stonesIn, BigDecimal stonesOut, BigDecimal caratsIn, BigDecimal caratsOut) {

        public long totalSeconds() {
            return waitingSeconds + activeSeconds;
        }
    }

    /** A batch that finished within the window. */
    public record Journey(String routeCode, long leadSeconds, long parkingSeconds) {
    }

    public record Result(Map<UUID, Totals> stages, List<Journey> journeys) {
    }

    /**
     * @param parkingStages stages whose time counts as parking in a journey
     * @param until         the end of the window: open visits count up to it, later events are ignored
     */
    public static Result compute(List<BatchRecord> batches, Set<UUID> parkingStages, Instant until) {
        Map<UUID, Accumulator> sums = new HashMap<>();
        List<Journey> journeys = new ArrayList<>();
        for (BatchRecord batch : batches) {
            UUID stage = null;
            Instant queuedAt = null;
            Instant startedAt = null;
            Instant firstQueued = null;
            Instant finishedAt = null;
            long parking = 0;
            BigDecimal stones = batch.stones();
            BigDecimal carats = batch.carats();
            for (BatchHistory.Event event : batch.events()) {
                if (event.occurredAt().isAfter(until)) {
                    break;
                }
                if (event.type() == EventType.STAGE_QUEUED) {
                    stage = event.stageId();
                    queuedAt = event.occurredAt();
                    startedAt = null;
                    finishedAt = null;
                    if (firstQueued == null) {
                        firstQueued = queuedAt;
                    }
                    sums.computeIfAbsent(stage, id -> new Accumulator()).visits++;
                } else if (event.type() == EventType.STAGE_STARTED && stage != null) {
                    startedAt = event.occurredAt();
                    sums.get(stage).waiting += seconds(queuedAt, startedAt);
                } else if (event.type() == EventType.STAGE_COMPLETED && stage != null && startedAt != null) {
                    Accumulator at = sums.get(stage);
                    at.active += seconds(startedAt, event.occurredAt());
                    at.completed++;
                    at.stonesIn = at.stonesIn.add(stones);
                    at.caratsIn = at.caratsIn.add(carats);
                    stones = event.stones();
                    carats = event.carats();
                    at.stonesOut = at.stonesOut.add(stones);
                    at.caratsOut = at.caratsOut.add(carats);
                    if (parkingStages.contains(stage)) {
                        parking += seconds(queuedAt, event.occurredAt());
                    }
                    finishedAt = event.occurredAt();
                    stage = null;
                }
            }
            if (stage != null) {
                // Still at the stage when the window ends.
                if (startedAt == null) {
                    sums.get(stage).waiting += seconds(queuedAt, until);
                } else {
                    sums.get(stage).active += seconds(startedAt, until);
                }
            } else if (finishedAt != null && firstQueued != null) {
                journeys.add(new Journey(batch.routeCode(), seconds(firstQueued, finishedAt), parking));
            }
        }
        Map<UUID, Totals> totals = new HashMap<>();
        sums.forEach((id, s) -> totals.put(id, new Totals(s.visits, s.waiting, s.active, s.completed, s.stonesIn,
                s.stonesOut, s.caratsIn, s.caratsOut)));
        return new Result(totals, journeys);
    }

    private static final class Accumulator {
        long visits;
        long waiting;
        long active;
        long completed;
        BigDecimal stonesIn = BigDecimal.ZERO;
        BigDecimal stonesOut = BigDecimal.ZERO;
        BigDecimal caratsIn = BigDecimal.ZERO;
        BigDecimal caratsOut = BigDecimal.ZERO;
    }

    private static long seconds(Instant from, Instant to) {
        return Math.max(0, to.getEpochSecond() - from.getEpochSecond());
    }
}
