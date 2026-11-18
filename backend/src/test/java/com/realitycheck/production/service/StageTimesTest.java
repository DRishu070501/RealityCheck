package com.realitycheck.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.service.BatchHistory.Event;
import com.realitycheck.production.service.StageTimes.BatchRecord;
import com.realitycheck.production.service.StageTimes.Journey;
import com.realitycheck.production.service.StageTimes.Result;
import com.realitycheck.production.service.StageTimes.Totals;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Waiting and active time, yields and finished journeys, against figures worked out by hand. */
class StageTimesTest {

    private static final Instant T0 = Instant.parse("2026-01-05T03:30:00Z");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID PARK = UUID.randomUUID();
    private static final UUID SAW = UUID.randomUUID();

    private long seq = 1;

    @Test
    void waitingIsQueuedToStartedActiveIsStartedToCompletedAndYieldsCountCompletedVisits() {
        // A: Plan queued 0 h, started 2, done 5 (10 -> 10 stones); Park queued 5, started 6, done 30;
        //    Saw queued 30, started 31, done 33 (10 -> 9 stones, 12 -> 11 carats): finished at 33 h.
        BatchRecord a = batch("TOPS", "10", "12",
                queued(PLAN, 0), started(PLAN, 2), completed(PLAN, 5, "10", "12"),
                queued(PARK, 5), started(PARK, 6), completed(PARK, 30, "10", "12"),
                queued(SAW, 30), started(SAW, 31), completed(SAW, 33, "9", "11"));
        // B: Plan queued 1 h, started 4, done 6 (20 -> 20 stones), then queued at Saw at 6 and still waiting.
        BatchRecord b = batch("FULL", "20", "25",
                queued(PLAN, 1), started(PLAN, 4), completed(PLAN, 6, "20", "25"), queued(SAW, 6));

        Result result = StageTimes.compute(List.of(a, b), Set.of(PARK), at(100));

        // Plan: waiting 2 + 3 = 5 h, active 3 + 2 = 5 h; 30 stones in and out over two completed visits.
        assertThat(result.stages().get(PLAN)).isEqualTo(totals(2, 5, 5, 2, "30", "30", "37", "37"));
        // Park: waiting 1 h, held 24 h.
        assertThat(result.stages().get(PARK)).isEqualTo(totals(1, 1, 24, 1, "10", "10", "12", "12"));
        // Saw: A waited 1 h and worked 2 h; B has waited 94 h (6 h to the end of the window at 100 h).
        assertThat(result.stages().get(SAW)).isEqualTo(totals(2, 95, 2, 1, "10", "9", "12", "11"));
        // Only A finished: 33 h from first queueing, 25 h of it at parking (queued 5 h to done 30 h).
        assertThat(result.journeys()).containsExactly(new Journey("TOPS", 33 * 3600, 25 * 3600));
    }

    @Test
    void aVisitOpenAtTheEndOfTheWindowCountsUpToItAndLaterEventsAreIgnored() {
        BatchRecord queuedOnly = batch(null, "5", "5", queued(PLAN, 0));
        BatchRecord started = batch(null, "5", "5", queued(PARK, 0), started(PARK, 1), completed(PARK, 50, "5", "5"));

        Result result = StageTimes.compute(List.of(queuedOnly, started), Set.of(PARK), at(10));

        assertThat(result.stages().get(PLAN).waitingSeconds()).isEqualTo(10 * 3600);
        assertThat(result.stages().get(PARK).waitingSeconds()).isEqualTo(3600);
        assertThat(result.stages().get(PARK).activeSeconds()).isEqualTo(9 * 3600);
        assertThat(result.stages().get(PARK).completedVisits()).isZero();
        assertThat(result.journeys()).isEmpty();
    }

    private static Totals totals(long visits, long waitingHours, long activeHours, long completed, String stonesIn,
            String stonesOut, String caratsIn, String caratsOut) {
        return new Totals(visits, waitingHours * 3600, activeHours * 3600, completed, d(stonesIn), d(stonesOut),
                d(caratsIn), d(caratsOut));
    }

    private static BatchRecord batch(String route, String stones, String carats, Event... events) {
        return new BatchRecord(route, d(stones), d(carats), List.of(events));
    }

    private Event queued(UUID stage, int hour) {
        return new Event(UUID.randomUUID(), seq++, EventType.STAGE_QUEUED, stage, at(hour), null, null, null, null,
                false);
    }

    private Event started(UUID stage, int hour) {
        return new Event(UUID.randomUUID(), seq++, EventType.STAGE_STARTED, stage, at(hour), null, null, null, null,
                false);
    }

    private Event completed(UUID stage, int hour, String stones, String carats) {
        return new Event(UUID.randomUUID(), seq++, EventType.STAGE_COMPLETED, stage, at(hour), d(stones), d(carats),
                null, null, false);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value).setScale(4);
    }

    private static Instant at(int hour) {
        return T0.plusSeconds(hour * 3600L);
    }
}
