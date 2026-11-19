package com.realitycheck.ai.service;

import com.realitycheck.common.config.RealityCheckProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * The daily spending cap on language-model calls (spec 11, task 15). Today's spend is counted from the tokens
 * stored on insights since midnight UTC, priced per million tokens from the configuration, so it survives a
 * restart and needs no table of its own. With no cap configured it never stops anything.
 *
 * <p>The check is made before a request: two requests at the same moment may both pass and overshoot the cap
 * by at most their own cost, which the rate limit keeps small.
 */
@Component
public class AiBudget {

    public static final String DAILY_BUDGET = "DAILY_BUDGET";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final InsightStore store;
    private final Clock clock;
    private final BigDecimal cap;
    private final BigDecimal inputPrice;
    private final BigDecimal outputPrice;

    public AiBudget(InsightStore store, Clock clock, RealityCheckProperties properties) {
        this.store = store;
        this.clock = clock;
        RealityCheckProperties.Ai ai = properties.ai();
        this.cap = ai.dailyBudgetUsd() == null || ai.dailyBudgetUsd().signum() <= 0 ? null : ai.dailyBudgetUsd();
        this.inputPrice = ai.inputUsdPerMtok();
        this.outputPrice = ai.outputUsdPerMtok();
    }

    /** True when a cap is set and today's spend has reached it. */
    public boolean exhausted() {
        return cap != null && spentToday().compareTo(cap) >= 0;
    }

    /** US dollars spent on language-model tokens since midnight UTC, to six decimals. */
    public BigDecimal spentToday() {
        Instant midnight = clock.instant().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        long[] tokens = store.tokensSince(midnight);
        return BigDecimal.valueOf(tokens[0]).multiply(inputPrice)
                .add(BigDecimal.valueOf(tokens[1]).multiply(outputPrice))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
