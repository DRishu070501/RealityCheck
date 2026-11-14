package com.realitycheck.simulation.service;

import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.engine.InvalidSimulationInputException;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Executes one run in the background: claim it, read its own snapshot (and nothing else), run the
 * engine outside any transaction, then store the results. A run either completes with every result
 * row or fails with none, and its failure message never carries a stack trace or class name.
 */
@Service
public class SimulationRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunExecutor.class);
    private static final int MAX_MESSAGE = 500;

    private final JdbcTemplate jdbc;
    private final SimulationResultWriter writer;
    private final Semaphore permits;
    private final Clock clock;
    private final SimulationEngine engine = new SimulationEngine();

    public SimulationRunExecutor(JdbcTemplate jdbc, SimulationResultWriter writer,
            @Qualifier("simulationPermits") Semaphore permits, Clock clock) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.permits = permits;
        this.clock = clock;
    }

    /** Runs the simulation and releases the submission's permit, whatever happens. */
    public void execute(UUID runId) {
        try {
            run(runId);
        } finally {
            permits.release();
        }
    }

    /**
     * The work itself, without touching the permit. Safe to call more than once for one run: only
     * the call that claims it does anything.
     */
    public void run(UUID runId) {
        UUID scenarioId = null;
        try {
            // Conditional claim: only one worker can move a run out of PENDING.
            int claimed = jdbc.update("""
                    update simulation_run set status = 'RUNNING', started_at = ?
                    where id = ? and status = 'PENDING'""", Timestamp.from(clock.instant()), runId);
            if (claimed == 0) {
                log.info("Simulation run not claimed, already taken or finished runId={}", runId);
                return;
            }
            String json = jdbc.queryForObject("select input_snapshot::text from simulation_run where id = ?",
                    String.class, runId);
            Snapshot snapshot = SnapshotJson.read(json);
            scenarioId = jdbc.queryForObject("select scenario_id from simulation_run where id = ?", UUID.class, runId);
            long started = System.nanoTime();
            SimulationResult result = engine.run(SnapshotInputAdapter.toInput(snapshot));
            long engineMillis = (System.nanoTime() - started) / 1_000_000;
            SimulationResultResponse response = SimulationResultMapper.toResponse(snapshot.factoryId(), snapshot,
                    result);
            writer.write(runId, response, result.trace());
            log.info("Simulation run completed runId={} scenarioId={} events={} termination={} engineMillis={}",
                    runId, scenarioId, result.eventsProcessed(), result.terminationReason(), engineMillis);
        } catch (Throwable failure) {
            log.error("Simulation run failed runId={} scenarioId={}", runId, scenarioId, failure);
            fail(runId, safeMessage(runId, failure));
            if (failure instanceof Error error && !(failure instanceof AssertionError)) {
                throw error;
            }
        }
    }

    void fail(UUID runId, String message) {
        try {
            jdbc.update("""
                    update simulation_run set status = 'FAILED', failure_message = ?, finished_at = ?
                    where id = ? and status in ('PENDING', 'RUNNING')""",
                    message, Timestamp.from(clock.instant()), runId);
        } catch (RuntimeException ex) {
            log.error("Could not mark simulation run failed runId={}", runId, ex);
        }
    }

    /** Engine validation messages are written for users; anything else gets a reference only. */
    static String safeMessage(UUID runId, Throwable failure) {
        String message = failure instanceof InvalidSimulationInputException invalid
                ? "The scenario cannot be simulated: " + invalid.getMessage()
                : "The simulation could not be completed. Reference: " + runId;
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 1) + "…";
    }
}
