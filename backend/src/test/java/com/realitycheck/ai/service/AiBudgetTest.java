package com.realitycheck.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.realitycheck.common.config.RealityCheckProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The daily cap on language-model spending (spec 11, task 15), priced at $0.10 and $0.50 per million tokens. */
class AiBudgetTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T18:30:00Z"), ZoneOffset.UTC);

    private final InsightStore store = mock(InsightStore.class);

    private AiBudget budget(String cap) {
        RealityCheckProperties properties = new RealityCheckProperties(new RealityCheckProperties.Cors(List.of()),
                new RealityCheckProperties.Ai("anthropic", "k", "claude-haiku-5-5", null, 30, 2, 4000, 20,
                        cap == null ? null : new BigDecimal(cap), new BigDecimal("0.10"), new BigDecimal("0.50")),
                new RealityCheckProperties.Demo(false, false, null));
        return new AiBudget(store, CLOCK, properties);
    }

    @Test
    void withoutACapNothingIsCountedOrStopped() {
        assertThat(budget(null).exhausted()).isFalse();
        assertThat(budget("0").exhausted()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    void todaysTokensArePricedPerMillionSinceMidnightUtc() {
        // 1,000,000 input tokens at $0.10 and 1,800,000 output tokens at $0.50: $1.00 exactly.
        when(store.tokensSince(any())).thenReturn(new long[] {1_000_000, 1_800_000});

        assertThat(budget("1.00").spentToday()).isEqualByComparingTo("1.00");
        verify(store).tokensSince(Instant.parse("2026-12-01T00:00:00Z"));
    }

    @Test
    void theCapIsReachedWhenTodaysSpendMeetsIt() {
        when(store.tokensSince(any())).thenReturn(new long[] {1_000_000, 1_800_000});
        assertThat(budget("1.00").exhausted()).isTrue();
        assertThat(budget("1.01").exhausted()).isFalse();

        when(store.tokensSince(any())).thenReturn(new long[] {999_990, 1_800_000});
        assertThat(budget("1.00").exhausted()).isFalse();
    }
}
