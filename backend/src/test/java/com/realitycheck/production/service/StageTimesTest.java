package com.realitycheck.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.service.BatchHistory.Event;
import com.realitycheck.production.service.StageTimes.Totals;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Waiting and active time per stage, against figures worked out by hand. */
class StageTimesTest {

    private static final Instant T0 = Instant.parse("2026-01-05T03:30:00Z");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID PARK = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10.0000");

    private long seq = 1;

    @Test
    void waitingIsQueuedToStartedAndActiveIsStartedToCompletedSummedOverVisits() {
        // Batch A: queued at Plan at 0 h, started 2 h, done 5 h; queued at Park 5 h, started 6 h, done 30 h.
        List<Event> a = new ArrayList<>(List.of(
                event(EventType.STAGE_QUEUED, PLAN, 0), event(EventType.STAGE_STARTED, PLAN, 2),
                event(EventType.STAGE_COMPLETED, PLAN, 5), event(EventType.STAGE_QUEUED, PARK, 5),
                event(EventType.STAGE_STARTED, PARK, 6), event(EventType.STAGE_COMPLETED, PARK, 30)));
        // Batch B: queued at Plan at 1 h, started 4 h, done 6 h.
        List<Event> b = List.of(event(EventType.STAGE_QUEUED, PLAN, 1), event(EventType.STAGE_STARTED, PLAN, 4),
                event(EventType.STAGE_COMPLETED, PLAN, 6));

        Map<UUID, Totals> totals = StageTimes.compute(List.of(a, b), at(100));

        // Plan: waiting 2 + 3 = 5 h, active 3 + 2 = 5 h, two visits. Park: waiting 1 h, held 24 h.
        assertThat(totals.get(PLAN)).isEqualTo(new Totals(2, 5 * 3600, 5 * 3600));
        assertThat(totals.get(PARK)).isEqualTo(new Totals(1, 3600, 24 * 3600));
    }

    @Test
    void aVisitOpenAtTheEndOfTheWindowCountsUpToItAndLaterEventsAreIgnored() {
        List<Event> queuedOnly = List.of(event(EventType.STAGE_QUEUED, PLAN, 0));
        List<Event> started = List.of(event(EventType.STAGE_QUEUED, PARK, 0), event(EventType.STAGE_STARTED, PARK, 1),
                event(EventType.STAGE_COMPLETED, PARK, 50));

        Map<UUID, Totals> totals = StageTimes.compute(List.of(queuedOnly, started), at(10));

        assertThat(totals.get(PLAN)).isEqualTo(new Totals(1, 10 * 3600, 0));
        assertThat(totals.get(PARK)).isEqualTo(new Totals(1, 3600, 9 * 3600));
    }

    private Event event(EventType type, UUID stage, int hour) {
        BigDecimal stones = type == EventType.STAGE_COMPLETED ? TEN : null;
        return new Event(UUID.randomUUID(), seq++, type, stage, at(hour), stones, stones, null, null, false);
    }

    private static Instant at(int hour) {
        return T0.plusSeconds(hour * 3600L);
    }
}
