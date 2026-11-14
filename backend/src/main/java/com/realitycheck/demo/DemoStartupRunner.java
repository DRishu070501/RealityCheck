package com.realitycheck.demo;

import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.factory.service.FactoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the demonstration factory at start-up, when enabled and the database has no factory yet. */
@Component
class DemoStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoStartupRunner.class);

    private final RealityCheckProperties properties;
    private final FactoryService factoryService;
    private final DemoSeeder seeder;

    DemoStartupRunner(RealityCheckProperties properties, FactoryService factoryService, DemoSeeder seeder) {
        this.properties = properties;
        this.factoryService = factoryService;
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.demo().seedOnStartup()) {
            return;
        }
        if (factoryService.count() > 0) {
            log.info("Demonstration seed skipped: a factory already exists");
            return;
        }
        seeder.seed();
    }
}
