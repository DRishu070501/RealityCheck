package com.realitycheck.scenario.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.ProductionReader;
import com.realitycheck.scenario.comparison.RunResultsPort.RunView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActualOutcomeServiceTest {

    private static final UUID FACTORY = UUID.randomUUID();
    private static final Instant FORK = Instant.parse("2026-11-09T06:00:00Z");

    private final FactoryStateService state = mock(FactoryStateService.class);
    private final ProductionReader reader = mock(ProductionReader.class);
    private final ActualOutcomeService service = new ActualOutcomeService(state, reader);

    @Test
    void thereIsNoActualOutcomeWhenNothingWasRecordedAfterTheFork() {
        when(reader.lastEventAt(FACTORY)).thenReturn(FORK);
        assertThat(service.after(run())).isEmpty();

        when(reader.lastEventAt(FACTORY)).thenReturn(FORK.minusSeconds(60));
        assertThat(service.after(run())).isEmpty();

        when(reader.lastEventAt(FACTORY)).thenReturn(null);
        assertThat(service.after(run())).isEmpty();

        verifyNoInteractions(state);
    }

    private static RunView run() {
        return new RunView(UUID.randomUUID(), UUID.randomUUID(), FACTORY, "COMPLETED", FORK, "hash", "1.0.0",
                "COMPLETED", "INR", null, null, List.of(), List.of(), List.of(), List.of());
    }
}
