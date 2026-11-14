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
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoStartupRunnerTest {

    private final FactoryService factoryService = mock(FactoryService.class);
    private final DemoSeeder seeder = mock(DemoSeeder.class);

    @Test
    void seedsAnEmptyDatabaseWhenEnabled() {
        when(factoryService.count()).thenReturn(0L);

        runner(true).run(null);

        verify(seeder).seed();
    }

    @Test
    void leavesExistingDataAlone() {
        when(factoryService.count()).thenReturn(1L);

        runner(true).run(null);

        verify(seeder, never()).seed();
    }

    @Test
    void doesNothingWhenDisabled() {
        when(factoryService.count()).thenReturn(0L);

        runner(false).run(null);

        verify(seeder, never()).seed();
    }

    private DemoStartupRunner runner(boolean seedOnStartup) {
        RealityCheckProperties properties = new RealityCheckProperties(
                new Cors(List.of()), new Ai("none", null), new Demo(seedOnStartup, false));
        return new DemoStartupRunner(properties, factoryService, seeder);
    }
}
