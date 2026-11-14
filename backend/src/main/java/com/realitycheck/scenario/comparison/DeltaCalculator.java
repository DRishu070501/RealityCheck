package com.realitycheck.scenario.comparison;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Differences between a baseline and an alternative value. Exact decimal arithmetic; nothing is
 * estimated. "Improved" describes how a metric moved under the simulation's assumptions; it is not
 * a recommendation.
 */
public final class DeltaCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DeltaCalculator() {
    }

    public enum BetterWhen {
        LOWER,
        HIGHER
    }

    public enum Direction {
        IMPROVED,
        WORSENED,
        UNCHANGED
    }

    /** {@code alternative - baseline}; null when either is null. */
    public static BigDecimal absolute(BigDecimal baseline, BigDecimal alternative) {
        return baseline == null || alternative == null ? null : alternative.subtract(baseline);
    }

    /** Percentage change at scale 2, half-even; null when either is null or the baseline is zero. */
    public static BigDecimal percent(BigDecimal baseline, BigDecimal alternative) {
        if (baseline == null || alternative == null || baseline.signum() == 0) {
            return null;
        }
        return alternative.subtract(baseline).multiply(HUNDRED).divide(baseline.abs(), 2, RoundingMode.HALF_EVEN);
    }

    /** Null when the metric has no better direction or the difference is unknown. */
    public static Direction direction(BetterWhen betterWhen, BigDecimal absolute) {
        if (betterWhen == null || absolute == null) {
            return null;
        }
        if (absolute.signum() == 0) {
            return Direction.UNCHANGED;
        }
        return (absolute.signum() < 0) == (betterWhen == BetterWhen.LOWER) ? Direction.IMPROVED : Direction.WORSENED;
    }
}
