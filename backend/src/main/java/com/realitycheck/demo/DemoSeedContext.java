package com.realitycheck.demo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What every seed section shares: a fixed anchor instant and deterministic ids. Nothing in the
 * seed reads the clock or generates a random value, so two seeds produce identical rows.
 */
public final class DemoSeedContext {

    /** Monday 5 January 2026, 09:00 in Asia/Kolkata: the start of the demonstration factory's first shift. */
    public static final Instant ANCHOR = Instant.parse("2026-01-05T03:30:00Z");

    public static final String FACTORY = "factory";

    /** @return the same id for the same key, every time */
    public UUID id(String key) {
        return UUID.nameUUIDFromBytes(("realitycheck-demo:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public UUID factoryId() {
        return id(FACTORY);
    }

    public Instant anchor() {
        return ANCHOR;
    }

    public Instant anchorPlus(Duration offset) {
        return ANCHOR.plus(offset);
    }
}
