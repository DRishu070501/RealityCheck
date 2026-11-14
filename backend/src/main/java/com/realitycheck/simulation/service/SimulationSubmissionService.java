package com.realitycheck.simulation.service;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.scenario.service.ScenarioSnapshotService;
import com.realitycheck.scenario.service.ScenarioSnapshotService.Assembled;
import com.realitycheck.scenario.snapshot.SimulationEngineContract;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import com.realitycheck.simulation.api.RunDtos.RunSubmittedResponse;
import com.realitycheck.simulation.domain.RunStatus;
import com.realitycheck.simulation.domain.SimulationRun;
import com.realitycheck.simulation.persistence.SimulationRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts a run: idempotency, reuse of an identical completed run, a permit, the PENDING row with
 * its immutable snapshot, then hand-off to the background executor after the row is committed.
 */
@Service
public class SimulationSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SimulationSubmissionService.class);
    static final int MAX_KEY_LENGTH = 100;

    private final ScenarioSnapshotService snapshotService;
    private final SimulationEngineContract engine;
    private final SimulationRunRepository runs;
    private final SimulationRunExecutor runExecutor;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore permits;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SimulationSubmissionService(ScenarioSnapshotService snapshotService, SimulationEngineContract engine,
            SimulationRunRepository runs, SimulationRunExecutor runExecutor,
            @Qualifier("simulationExecutor") ThreadPoolTaskExecutor executor,
            @Qualifier("simulationPermits") Semaphore permits, TransactionTemplate transactions, Clock clock) {
        this.snapshotService = snapshotService;
        this.engine = engine;
        this.runs = runs;
        this.runExecutor = runExecutor;
        this.executor = executor;
        this.permits = permits;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param idempotencyKeyOrNull a repeated key returns the run it first created
     * @param force                run again even if an identical completed run exists
     */
    public RunSubmittedResponse submit(UUID scenarioId, String idempotencyKeyOrNull, boolean force) {
        String key = idempotencyKeyOrNull == null || idempotencyKeyOrNull.isBlank() ? null
                : idempotencyKeyOrNull.strip();
        if (key != null && key.length() > MAX_KEY_LENGTH) {
            throw new ValidationException("Idempotency-Key", "must be at most " + MAX_KEY_LENGTH + " characters");
        }
        if (key != null) {
            Optional<SimulationRun> existing = runs.findByScenarioIdAndIdempotencyKey(scenarioId, key);
            if (existing.isPresent()) {
                return reused(existing.get());
            }
        }

        Assembled assembled = snapshotService.assemble(scenarioId);
        if (!assembled.valid()) {
            throw ApiException.unprocessable("The scenario cannot be simulated. Fix the listed problems first.")
                    .withProperty("errors", assembled.errors());
        }
        if (!force) {
            Optional<SimulationRun> identical = runs.findFirstByScenarioIdAndInputHashAndStatusOrderBySubmittedAtDesc(
                    scenarioId, assembled.inputHash(), RunStatus.COMPLETED);
            if (identical.isPresent()) {
                return reused(identical.get());
            }
        }

        if (!permits.tryAcquire()) {
            throw ApiException.tooManyRequests("Too many simulations are running or queued. Try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        SimulationRun run;
        try {
            Instant now = clock.instant();
            SimulationRun pending = new SimulationRun(UUID.randomUUID(), now, scenarioId, assembled.factoryId(), key,
                    assembled.snapshot().forkAt(), SnapshotJson.write(assembled.snapshot()), assembled.inputHash(),
                    assembled.baseStateHash(), engine.algorithmVersion());
            run = transactions.execute(status -> runs.saveAndFlush(pending));
        } catch (DataIntegrityViolationException race) {
            permits.release();
            // Another request with the same key won the insert; return what it created.
            if (key != null) {
                Optional<SimulationRun> winner = runs.findByScenarioIdAndIdempotencyKey(scenarioId, key);
                if (winner.isPresent()) {
                    return reused(winner.get());
                }
            }
            throw race;
        } catch (RuntimeException ex) {
            permits.release();
            throw ex;
        }

        UUID runId = run.getId();
        try {
            executor.execute(() -> runExecutor.execute(runId));
        } catch (RuntimeException rejected) {
            // Cannot happen while permits equal threads plus queue slots; fail the run rather than strand it.
            permits.release();
            log.error("Simulation run could not be queued runId={}", runId, rejected);
            runExecutor.fail(runId, "The simulation could not be queued. Reference: " + runId);
            throw ApiException.tooManyRequests("The simulation could not be queued. Try again shortly.")
                    .withHeader("Retry-After", "5");
        }
        log.info("Simulation run submitted runId={} scenarioId={} inputHash={}", runId, scenarioId,
                assembled.inputHash());
        return new RunSubmittedResponse(runId, scenarioId, RunStatus.PENDING.name(), false, run.getSubmittedAt());
    }

    private static RunSubmittedResponse reused(SimulationRun run) {
        return new RunSubmittedResponse(run.getId(), run.getScenarioId(), run.getStatus().name(), true,
                run.getSubmittedAt());
    }
}
