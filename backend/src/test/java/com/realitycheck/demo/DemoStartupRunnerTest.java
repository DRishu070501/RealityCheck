package com.realitycheck.demo;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.common.config.RealityCheckProperties.Ai;
import com.realitycheck.common.config.RealityCheckProperties.Cors;
import com.realitycheck.common.config.RealityCheckProperties.Demo;
import com.realitycheck.factory.service.FactoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoStartupRunnerTest {

    private static final Instant NOW = Instant.parse("2026-01-21T06:00:00Z");

    private final FactoryService factoryService = mock(FactoryService.class);
    private final DemoSeeder seeder = mock(DemoSeeder.class);

    @Test
    void seedsAnEmptyDatabaseWhenEnabled() {
        when(factoryService.count()).thenReturn(0L);

        runner(true).run(null);

        verify(seeder).seed(NOW);
    }

    @Test
    void leavesExistingDataAlone() {
        when(factoryService.count()).thenReturn(1L);

        runner(true).run(null);

        verify(seeder, never()).seed(NOW);
    }

    @Test
    void doesNothingWhenDisabled() {
        when(factoryService.count()).thenReturn(0L);

        runner(false).run(null);

        verify(seeder, never()).seed(NOW);
    }

    private DemoStartupRunner runner(boolean seedOnStartup) {
        RealityCheckProperties properties = new RealityCheckProperties(
                new Cors(List.of()), new Ai("none", null), new Demo(seedOnStartup, false));
        return new DemoStartupRunner(properties, factoryService, seeder, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
