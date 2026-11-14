package com.realitycheck.simulation.service;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs are executed in memory, so a run that was pending or running when the application stopped
 * will never finish. At startup they are marked failed, so no client polls one forever.
 */
@Component
public class StaleRunRecovery implements ApplicationRunner {

    public static final String MESSAGE = "Interrupted by restart";

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StaleRunRecovery(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = jdbc.update("""
                update simulation_run set status = 'FAILED', failure_message = ?, finished_at = ?
                where status in ('PENDING', 'RUNNING')""", MESSAGE, Timestamp.from(clock.instant()));
        if (recovered > 0) {
            log.warn("Marked {} interrupted simulation runs as failed", recovered);
        }
    }
}
