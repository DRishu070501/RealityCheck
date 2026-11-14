package com.realitycheck.scenario.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.scenario.comparison.DeltaCalculator.BetterWhen;
import com.realitycheck.scenario.comparison.DeltaCalculator.Direction;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

/** Spec 08, requirement 2. */
class DeltaCalculatorTest {

    @Test
    void absoluteIsAlternativeMinusBaselineAndNullWhenEitherIsNull() {
        assertThat(DeltaCalculator.absolute(new BigDecimal("432000"), new BigDecimal("388800")))
                .isEqualByComparingTo("-43200");
        assertThat(DeltaCalculator.absolute(null, BigDecimal.ONE)).isNull();
        assertThat(DeltaCalculator.absolute(BigDecimal.ONE, null)).isNull();
    }

    @Test
    void percentIsRelativeToTheBaselineAtScaleTwoAndNullForAZeroBaseline() {
        assertThat(DeltaCalculator.percent(new BigDecimal("432000"), new BigDecimal("388800")))
                .isEqualTo(new BigDecimal("-10.00"));
        assertThat(DeltaCalculator.percent(new BigDecimal("3"), new BigDecimal("4")))
                .isEqualTo(new BigDecimal("33.33"));
        assertThat(DeltaCalculator.percent(new BigDecimal("6"), new BigDecimal("5")))
                .isEqualTo(new BigDecimal("-16.67"));
        assertThat(DeltaCalculator.percent(BigDecimal.ZERO, BigDecimal.ONE)).isNull();
        assertThat(DeltaCalculator.percent(null, BigDecimal.ONE)).isNull();
    }

    @Test
    void directionFollowsWhichWayIsBetter() {
        assertThat(DeltaCalculator.direction(BetterWhen.LOWER, new BigDecimal("-1"))).isEqualTo(Direction.IMPROVED);
        assertThat(DeltaCalculator.direction(BetterWhen.LOWER, BigDecimal.ONE)).isEqualTo(Direction.WORSENED);
        assertThat(DeltaCalculator.direction(BetterWhen.HIGHER, BigDecimal.ONE)).isEqualTo(Direction.IMPROVED);
        assertThat(DeltaCalculator.direction(BetterWhen.HIGHER, new BigDecimal("-0.5")))
                .isEqualTo(Direction.WORSENED);
        assertThat(DeltaCalculator.direction(BetterWhen.HIGHER, new BigDecimal("0.000")))
                .isEqualTo(Direction.UNCHANGED);
        assertThat(DeltaCalculator.direction(null, BigDecimal.ONE)).isNull();
        assertThat(DeltaCalculator.direction(BetterWhen.LOWER, null)).isNull();
    }

    @Property
    void absoluteIsAntisymmetric(@ForAll @Scale(4) @BigRange(min = "-1000000", max = "1000000") BigDecimal a,
            @ForAll @Scale(4) @BigRange(min = "-1000000", max = "1000000") BigDecimal b) {
        assertThat(DeltaCalculator.absolute(b, a)).isEqualByComparingTo(DeltaCalculator.absolute(a, b).negate());
    }

    @Property
    void aValueComparedWithItselfIsUnchanged(@ForAll @Scale(4) @BigRange(min = "0", max = "1000000") BigDecimal a) {
        BigDecimal absolute = DeltaCalculator.absolute(a, a);

        assertThat(absolute).isEqualByComparingTo("0");
        assertThat(DeltaCalculator.direction(BetterWhen.LOWER, absolute)).isEqualTo(Direction.UNCHANGED);
        if (a.signum() != 0) {
            assertThat(DeltaCalculator.percent(a, a)).isEqualByComparingTo("0");
        }
    }
}
