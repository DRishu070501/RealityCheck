package com.realitycheck.demo;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/**
 * What every seed section shares: the instant the seed runs for, an anchor derived from it, and
 * deterministic ids. Nothing in the seed reads the clock or generates a random value, so seeding
 * twice for the same instant produces identical rows.
 *
 * <p>The history is laid out relative to the seeding instant, so that the demonstration factory
 * always has recent activity and work in progress "now". The anchor is 09:00 factory time on the
 * Monday two weeks before the week of the seeding instant.
 */
public final class DemoSeedContext {

    public static final String FACTORY = "factory";

    private static final ZoneId ZONE = ZoneId.of(DemoData.TIME_ZONE);

    private final Instant seededAt;
    private final Instant anchor;

    public DemoSeedContext(Instant seededAt) {
        this.seededAt = seededAt;
        LocalDate monday = seededAt.atZone(ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(2);
        this.anchor = monday.atTime(9, 0).atZone(ZONE).toInstant();
    }

    /** @return the same id for the same key, every time */
    public UUID id(String key) {
        return UUID.nameUUIDFromBytes(("realitycheck-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public UUID factoryId() {
        return id(FACTORY);
    }

    /** The start of the demonstration factory's history. */
    public Instant anchor() {
        return anchor;
    }

    public Instant anchorPlus(Duration offset) {
        return anchor.plus(offset);
    }

    /** The demonstration's "now": history is generated up to this instant and no further. */
    public Instant seededAt() {
        return seededAt;
    }
}
