package com.realitycheck.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realitycheck.scenario.ScenarioTestSupport;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.service.SimulationResultMapper;
import com.realitycheck.simulation.service.SimulationResultWriter;
import com.realitycheck.simulation.service.SimulationRunExecutor;
import com.realitycheck.simulation.service.SnapshotInputAdapter;
import com.realitycheck.simulation.service.StaleRunRecovery;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Stored, asynchronous simulation runs (spec 07, Part B). */
class SimulationRunIT extends ScenarioTestSupport {

    private static final String[] HISTORY_AND_CONFIGURATION = {"factory", "working_shift", "resource",
            "resource_availability", "workflow", "workflow_stage", "stage_requirement", "production_order",
            "production_batch", "operational_event", "scenario", "scenario_change"};

    @Autowired
    private SimulationRunExecutor executor;

    @Autowired
    private SimulationResultWriter writer;

    @Autowired
    private StaleRunRecovery recovery;

    @Autowired
    @Qualifier("simulationPermits")
    private Semaphore permits;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aSubmittedRunCompletesInTheBackgroundAndEveryResultIsReadable() {
        String scenarioId = createScenario("Baseline");

        ResponseEntity<JsonNode> submitted = simulate(scenarioId, null, false);

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submitted.getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(submitted.getBody().path("reused").asBoolean()).isFalse();
        String runId = submitted.getBody().path("runId").asText();
        assertThat(submitted.getHeaders().getLocation().getPath()).isEqualTo("/api/simulations/" + runId);

        JsonNode run = awaitFinished(runId);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("scenarioName").asText()).isEqualTo("Baseline");
        assertThat(run.path("algorithmVersion").asText()).isEqualTo(SimulationEngine.ALGORITHM_VERSION);
        assertThat(run.path("forkAt").asText()).isEqualTo(fork.toString());
        assertThat(run.path("terminationReason").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("inputHash").asText()).matches("[0-9a-f]{64}");
        assertThat(run.path("failureMessage").isNull()).isTrue();
        assertThat(run.path("startedAt").isNull()).isFalse();
        assertThat(run.path("finishedAt").isNull()).isFalse();

        JsonNode results = get("/api/simulations/" + runId + "/results").getBody();
        assertThat(results.path("source").asText()).isEqualTo("SIMULATED");
        assertThat(results.path("currencyCode").asText()).isEqualTo("INR");
        assertThat(results.path("summary").path("ordersTotal").asInt()).isEqualTo(2);
        assertThat(results.path("summary").path("ordersCompleted").asInt()).isEqualTo(2);
        assertThat(results.path("summary").path("inputStones").asText()).isEqualTo("90.0000");
        assertThat(results.path("orders")).hasSize(2);
        assertThat(results.path("resources")).hasSize(4);
        assertThat(texts(results.path("resources"), "name")).containsExactly("Saws", "Spare", "Wheels", "Polishers");
        assertThat(results.path("resources").get(2).path("capacity").asInt()).isEqualTo(2);
        assertThat(results.path("bottleneck").path("explanation").asText()).isNotBlank();

        JsonNode stages = get("/api/simulations/" + runId + "/stages").getBody();
        assertThat(texts(stages, "code")).containsExactly("CUTTING", "POLISHING");
        assertThat(stages.get(1).path("bottleneck").path("score").asText()).isNotBlank();
        assertThat(get("/api/simulations/" + runId + "/resources").getBody()).hasSize(4);

        JsonNode events = get("/api/simulations/" + runId + "/events?size=5").getBody();
        assertThat(events.path("items")).hasSize(5);
        assertThat(events.path("totalItems").asLong()).isGreaterThan(5);
        assertThat(texts(events.path("items"), "seq")).isSorted();
        JsonNode polishingEvents = get("/api/simulations/" + runId + "/events?size=200&stageId=" + polishing).getBody();
        assertThat(texts(polishingEvents.path("items"), "stageId")).containsOnly(polishing);
        assertThat(texts(polishingEvents.path("items"), "eventType")).contains("JOB_STARTED", "JOB_COMPLETED");

        JsonNode runs = get("/api/scenarios/" + scenarioId + "/simulations").getBody();
        assertThat(texts(runs.path("items"), "runId")).containsExactly(runId);
        JsonNode scenario = get("/api/scenarios/" + scenarioId).getBody();
        assertThat(scenario.path("latestRun").path("runId").asText()).isEqualTo(runId);
        assertThat(scenario.path("latestRun").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(get("/api/scenarios?factoryId=" + factoryId).getBody().path("items").get(0)
                .path("latestRun").path("runId").asText()).isEqualTo(runId);
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheOriginalRun() {
        String scenarioId = createScenario("Baseline");

        ResponseEntity<JsonNode> first = simulate(scenarioId, "key-1", false);
        ResponseEntity<JsonNode> second = simulate(scenarioId, "key-1", true);

        assertThat(second.getBody().path("runId").asText()).isEqualTo(first.getBody().path("runId").asText());
        assertThat(second.getBody().path("reused").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from simulation_run", Long.class)).isEqualTo(1);
        awaitFinished(first.getBody().path("runId").asText());
        assertThat(errorFields(simulate(scenarioId, "k".repeat(101), false))).containsExactly("Idempotency-Key");
    }

    @Test
    void anIdenticalCompletedRunIsReusedUnlessForcedAndAForcedRunGivesTheSameNumbers() {
        String scenarioId = createScenario("Baseline");
        String runId = runToCompletion(scenarioId);

        ResponseEntity<JsonNode> again = simulate(scenarioId, null, false);
        ResponseEntity<JsonNode> forced = simulate(scenarioId, null, true);

        assertThat(again.getBody().path("runId").asText()).isEqualTo(runId);
        assertThat(again.getBody().path("reused").asBoolean()).isTrue();
        assertThat(again.getBody().path("status").asText()).isEqualTo("COMPLETED");
        String forcedId = forced.getBody().path("runId").asText();
        assertThat(forcedId).isNotEqualTo(runId);
        assertThat(awaitFinished(forcedId).path("status").asText()).isEqualTo("COMPLETED");
        JsonNode original = get("/api/simulations/" + runId + "/results").getBody();
        JsonNode repeated = get("/api/simulations/" + forcedId + "/results").getBody();
        assertThat(repeated.path("summary")).isEqualTo(original.path("summary"));
        assertThat(repeated.path("orders")).isEqualTo(original.path("orders"));
        assertThat(get("/api/simulations/" + forcedId + "/stages").getBody())
                .isEqualTo(get("/api/simulations/" + runId + "/stages").getBody());
    }

    @Test
    void editingConfigurationOrTheScenarioAfterARunLeavesTheRunUntouched() {
        String scenarioId = createScenario("Plan");
        addChange(scenarioId, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"DELTA\", \"value\": 1}".formatted(wheels));
        String runId = runToCompletion(scenarioId);
        String storedRun = jdbc.queryForObject("""
                select md5(input_snapshot::text || input_hash || base_state_hash || algorithm_version || summary::text)
                from simulation_run where id = ?::uuid""", String.class, runId);
        JsonNode results = get("/api/simulations/" + runId + "/results").getBody();
        JsonNode stages = get("/api/simulations/" + runId + "/stages").getBody();

        put("/api/resources/" + wheels, """
                {"name": "Wheels renamed", "category": "TEST_CATEGORY", "capacity": 5, "hourlyCost": "120.00",
                 "status": "ACTIVE", "version": 0}""");
        addChange(scenarioId, "DEMAND_VOLUME", "{\"percent\": 40}");

        assertThat(jdbc.queryForObject("""
                select md5(input_snapshot::text || input_hash || base_state_hash || algorithm_version || summary::text)
                from simulation_run where id = ?::uuid""", String.class, runId)).isEqualTo(storedRun);
        assertThat(get("/api/simulations/" + runId + "/results").getBody()).isEqualTo(results);
        assertThat(get("/api/simulations/" + runId + "/stages").getBody()).isEqualTo(stages);
        assertThat(texts(get("/api/simulations/" + runId + "/resources").getBody(), "name")).contains("Wheels");
    }

    @Test
    void theStoredInputOfARunCannotBeRewritten() {
        String runId = runToCompletion(createScenario("Baseline"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update simulation_run set input_hash = repeat('0', 64) where id = ?::uuid", runId))
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update simulation_stage_result set jobs_completed = 0 where run_id = ?::uuid", runId))
                .hasMessageContaining("append-only");
    }

    @Test
    void aRunChangesNoHistoryOrConfiguration() {
        String scenarioId = createScenario("Plan");
        addChange(scenarioId, "DEMAND_VOLUME", "{\"percent\": 25}");
        String before = fingerprint(HISTORY_AND_CONFIGURATION);

        runToCompletion(scenarioId);

        assertThat(fingerprint(HISTORY_AND_CONFIGURATION)).isEqualTo(before);
    }

    @Test
    void anInvalidScenarioIsRejectedWith422AndNoRunIsCreated() {
        String scenarioId = createScenario("No wheels");
        addChange(scenarioId, "RESOURCE_CAPACITY", "{\"resourceId\": \"%s\", \"mode\": \"ABSOLUTE\", \"value\": 0}".formatted(wheels));

        ResponseEntity<JsonNode> response = simulate(scenarioId, null, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(texts(response.getBody().path("errors"), "code")).contains("REQUIREMENT_EXCEEDS_CAPACITY");
        assertThat(jdbc.queryForObject("select count(*) from simulation_run", Long.class)).isZero();
    }

    @Test
    void withNoFreePermitASubmissionIsRefusedWith429AndCreatesNothing() throws InterruptedException {
        String scenarioId = createScenario("Baseline");
        int available = permits.drainPermits();
        try {
            ResponseEntity<JsonNode> response = simulate(scenarioId, null, false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(jdbc.queryForObject("select count(*) from simulation_run", Long.class)).isZero();
        } finally {
            permits.release(available);
        }
    }

    @Test
    void resultsAreRefusedWith409UntilTheRunCompletes() {
        String runId = runToCompletion(createScenario("Baseline"));
        String pending = copyRun(runId, "PENDING", SimulationEngine.ALGORITHM_VERSION, false);

        ResponseEntity<JsonNode> response = get("/api/simulations/" + pending + "/results");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(get("/api/simulations/" + pending + "/stages").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/api/simulations/" + pending).getBody().path("status").asText()).isEqualTo("PENDING");
        assertThat(get("/api/simulations/00000000-0000-0000-0000-000000000009").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFailureWhileStoringResultsKeepsNoResultRows() {
        String runId = runToCompletion(createScenario("Baseline"));
        // A copy still PENDING: the writer inserts every row, then finds the run is not RUNNING and throws.
        String pending = copyRun(runId, "PENDING", SimulationEngine.ALGORITHM_VERSION, false);
        Snapshot snapshot = SnapshotJson.read(jdbc.queryForObject(
                "select input_snapshot::text from simulation_run where id = ?::uuid", String.class, pending));
        var result = new SimulationEngine().run(SnapshotInputAdapter.toInput(snapshot));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(UUID.fromString(pending),
                SimulationResultMapper.toResponse(snapshot.factoryId(), snapshot, result), result.trace()))
                .isInstanceOf(IllegalStateException.class);

        for (String table : List.of("simulation_stage_result", "simulation_resource_result", "simulation_event_result")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where run_id = ?::uuid", Long.class,
                    pending)).as(table).isZero();
        }
    }

    @Test
    void anExecutionFailureMarksTheRunFailedWithAMessageThatHidesInternals() {
        String runId = runToCompletion(createScenario("Baseline"));
        String broken = copyRun(runId, "PENDING", SimulationEngine.ALGORITHM_VERSION, false);
        jdbc.execute("alter table simulation_run disable trigger simulation_run_input_immutable");
        try {
            jdbc.update("update simulation_run set input_snapshot = '{\"schemaVersion\": 1}'::jsonb where id = ?::uuid",
                    broken);
        } finally {
            jdbc.execute("alter table simulation_run enable trigger simulation_run_input_immutable");
        }

        executor.run(UUID.fromString(broken));

        JsonNode run = get("/api/simulations/" + broken).getBody();
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("failureMessage").asText())
                .isEqualTo("The simulation could not be completed. Reference: " + broken)
                .doesNotContain("Exception", "com.realitycheck");
        assertThat(jdbc.queryForObject("select count(*) from simulation_stage_result where run_id = ?::uuid",
                Long.class, broken)).isZero();
    }

    @Test
    void onlyOneWorkerCanClaimARun() throws InterruptedException {
        String runId = runToCompletion(createScenario("Baseline"));
        String pending = copyRun(runId, "PENDING", SimulationEngine.ALGORITHM_VERSION, false);
        CountDownLatch start = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                start.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            executor.run(UUID.fromString(pending));
        };
        Thread first = new Thread(work);
        Thread second = new Thread(work);
        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.MINUTES.toMillis(2));
        second.join(TimeUnit.MINUTES.toMillis(2));

        assertThat(get("/api/simulations/" + pending).getBody().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from simulation_stage_result where run_id = ?::uuid",
                Long.class, pending)).isEqualTo(2);
    }

    @Test
    void runsInterruptedByARestartAreMarkedFailed() {
        String runId = runToCompletion(createScenario("Baseline"));
        String pending = copyRun(runId, "PENDING", SimulationEngine.ALGORITHM_VERSION, false);
        String running = copyRun(runId, "RUNNING", SimulationEngine.ALGORITHM_VERSION, false);

        recovery.run(null);

        for (String id : List.of(pending, running)) {
            JsonNode run = get("/api/simulations/" + id).getBody();
            assertThat(run.path("status").asText()).isEqualTo("FAILED");
            assertThat(run.path("failureMessage").asText()).isEqualTo("Interrupted by restart");
        }
        assertThat(get("/api/simulations/" + runId).getBody().path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void theForkCannotMoveOnceAScenarioHasARun() {
        String scenarioId = createScenario("Baseline");
        runToCompletion(scenarioId);
        long version = get("/api/scenarios/" + scenarioId).getBody().path("version").asLong();

        ResponseEntity<JsonNode> moved = patch("/api/scenarios/" + scenarioId,
                "{\"forkAt\": \"%s\", \"version\": %d}".formatted(fork.minusSeconds(30), version));
        ResponseEntity<JsonNode> renamed = patch("/api/scenarios/" + scenarioId,
                "{\"name\": \"Still fine\", \"version\": %d}".formatted(version));

        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reExecutingAStoredSnapshotReproducesTheStoredSummary() throws Exception {
        String runId = runToCompletion(createScenario("Baseline"));
        Snapshot snapshot = SnapshotJson.read(jdbc.queryForObject(
                "select input_snapshot::text from simulation_run where id = ?::uuid", String.class, runId));

        var response = SimulationResultMapper.toResponse(snapshot.factoryId(), snapshot,
                new SimulationEngine().run(SnapshotInputAdapter.toInput(snapshot)));

        JsonNode stored = get("/api/simulations/" + runId + "/results").getBody();
        JsonNode summary = objectMapper.valueToTree(response.summary());
        JsonNode orders = objectMapper.valueToTree(response.orders());
        // Compared as text: the trees may differ in number node type (int against long) only.
        assertThat(summary.toString()).isEqualTo(stored.path("summary").toString());
        assertThat(orders.toString()).isEqualTo(stored.path("orders").toString());
    }

    @Test
    void storedResultsAreServedUnchangedUnderTheVersionThatProducedThem() {
        String runId = runToCompletion(createScenario("Baseline"));
        String older = copyRun(runId, "COMPLETED", "0.9.0", true);

        JsonNode original = get("/api/simulations/" + runId + "/results").getBody();
        JsonNode served = get("/api/simulations/" + older + "/results").getBody();

        assertThat(served.path("algorithmVersion").asText()).isEqualTo("0.9.0");
        assertThat(served.path("summary")).isEqualTo(original.path("summary"));
    }

    /** Copies a run's inputs (and, when asked, its summary and result rows) into a new row with the given status. */
    private String copyRun(String sourceId, String status, String version, boolean withResults) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into simulation_run (id, scenario_id, factory_id, status, fork_at, input_snapshot, input_hash,
                  base_state_hash, algorithm_version, termination_reason, simulated_end_at, events_processed, summary,
                  submitted_at, created_at, finished_at)
                select ?::uuid, scenario_id, factory_id, ?, fork_at, input_snapshot, input_hash, base_state_hash, ?,
                  case when ? then termination_reason end, case when ? then simulated_end_at end,
                  case when ? then events_processed end, case when ? then summary end,
                  submitted_at - interval '1 hour', now(), case when ? then finished_at end
                from simulation_run where id = ?::uuid""",
                id, status, version, withResults, withResults, withResults, withResults, withResults, sourceId);
        if (withResults) {
            jdbc.update("""
                    insert into simulation_resource_result (id, run_id, resource_id, kind, name, capacity,
                      busy_unit_seconds, available_unit_seconds, idle_unit_seconds, utilization,
                      utilization_null_reason, overcommitted, cost, created_at)
                    select gen_random_uuid(), ?::uuid, resource_id, kind, name, capacity, busy_unit_seconds,
                      available_unit_seconds, idle_unit_seconds, utilization, utilization_null_reason, overcommitted,
                      cost, created_at
                    from simulation_resource_result where run_id = ?::uuid""", id, sourceId);
        }
        return id;
    }
}
