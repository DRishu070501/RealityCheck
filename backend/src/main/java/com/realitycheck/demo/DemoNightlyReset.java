package com.realitycheck.demo;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Restores the demonstration factory on a schedule, for a public deployment where visitors may change it and
 * the reset is not offered in the app (spec 11, task 15). Active only when {@code realitycheck.demo.reset-cron}
 * is set, for example {@code 0 30 21 * * *} (21:30 UTC, 03:00 in India). Nothing is exposed over HTTP.
 *
 * <p>It erases everything, so it refuses to run when the database holds any factory that is not the
 * demonstration: a misconfigured instance with real data keeps it.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${realitycheck.demo.reset-cron:}' != ''")
public class DemoNightlyReset {

    private static final Logger log = LoggerFactory.getLogger(DemoNightlyReset.class);

    private final DemoSeeder seeder;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DemoNightlyReset(DemoSeeder seeder, JdbcTemplate jdbc, Clock clock) {
        this.seeder = seeder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${realitycheck.demo.reset-cron}", zone = "UTC")
    public void reset() {
        resetIfDemoOnly();
    }

    /** @return true when the reset ran */
    boolean resetIfDemoOnly() {
        Long other = jdbc.queryForObject("select count(*) from factory where not demo", Long.class);
        if (other != null && other > 0) {
            log.warn("Scheduled demo reset skipped: the database holds {} factory(ies) that are not the demonstration",
                    other);
            return false;
        }
        Instant now = clock.instant();
        log.info("Scheduled demo reset: factoryId={}", seeder.reset(now));
        return true;
    }
}
