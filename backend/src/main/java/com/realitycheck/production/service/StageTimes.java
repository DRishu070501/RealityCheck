package com.realitycheck.production.service;

import com.realitycheck.production.domain.EventType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where packets' time went at each stage, from recorded history (spec 11, tasks 12 and 13). A pure function
 * over each batch's effective events (corrections applied): for every visit, the calendar time from being
 * queued to being started is <em>waiting</em>, and from being started to being completed is <em>active</em>
 * (worked, at a working stage; held, at a parking, waiting or job-work stage). A visit still open at
 * {@code until} counts up to then. Nothing is estimated: a visit with no start has only waiting time.
 */
public final class StageTimes {

    private StageTimes() {
    }

    /** Calendar seconds summed over every visit to one stage. */
    public record Totals(long visits, long waitingSeconds, long activeSeconds) {

        public long totalSeconds() {
            return waitingSeconds + activeSeconds;
        }
    }

    /**
     * @param batches each batch's effective events, in order ({@link BatchHistory#effective})
     * @param until   the end of the window: open visits count up to it, later events are ignored
     * @return totals by stage id; stages never visited are absent
     */
    public static Map<UUID, Totals> compute(List<List<BatchHistory.Event>> batches, Instant until) {
        Map<UUID, long[]> sums = new HashMap<>();
        for (List<BatchHistory.Event> events : batches) {
            UUID stage = null;
            Instant queuedAt = null;
            Instant startedAt = null;
            for (BatchHistory.Event event : events) {
                if (event.occurredAt().isAfter(until)) {
                    break;
                }
                if (event.type() == EventType.STAGE_QUEUED) {
                    stage = event.stageId();
                    queuedAt = event.occurredAt();
                    startedAt = null;
                    sums.computeIfAbsent(stage, id -> new long[3])[0]++;
                } else if (event.type() == EventType.STAGE_STARTED && stage != null) {
                    startedAt = event.occurredAt();
                    sums.get(stage)[1] += seconds(queuedAt, startedAt);
                } else if (event.type() == EventType.STAGE_COMPLETED && stage != null && startedAt != null) {
                    sums.get(stage)[2] += seconds(startedAt, event.occurredAt());
                    stage = null;
                }
            }
            if (stage != null) {
                // Still at the stage when the window ends.
                if (startedAt == null) {
                    sums.get(stage)[1] += seconds(queuedAt, until);
                } else {
                    sums.get(stage)[2] += seconds(startedAt, until);
                }
            }
        }
        Map<UUID, Totals> totals = new HashMap<>();
        sums.forEach((id, s) -> totals.put(id, new Totals(s[0], s[1], s[2])));
        return totals;
    }

    private static long seconds(Instant from, Instant to) {
        return Math.max(0, to.getEpochSecond() - from.getEpochSecond());
    }
}
