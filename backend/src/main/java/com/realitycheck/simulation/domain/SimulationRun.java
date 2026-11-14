package com.realitycheck.simulation.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One simulation of a scenario. Its input columns are written once at submission and never
 * change (a database trigger enforces it). Status, timing and the summary are written by the
 * executor with explicit conditional updates, never through this entity, so the entity is
 * read-only after insert.
 */
@Entity
@Table(name = "simulation_run")
public class SimulationRun extends BaseEntity {

    @Column(name = "scenario_id", nullable = false, updatable = false)
    private UUID scenarioId;

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private RunStatus status;

    @Column(name = "idempotency_key", updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "fork_at", nullable = false, updatable = false)
    private Instant forkAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, updatable = false)
    private String inputSnapshot;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64)
    private String inputHash;

    @Column(name = "base_state_hash", nullable = false, updatable = false, length = 64)
    private String baseStateHash;

    @Column(name = "algorithm_version", nullable = false, updatable = false, length = 20)
    private String algorithmVersion;

    @Column(name = "termination_reason", updatable = false, length = 24)
    private String terminationReason;

    @Column(name = "simulated_end_at", updatable = false)
    private Instant simulatedEndAt;

    @Column(name = "events_processed", updatable = false)
    private Long eventsProcessed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String summary;

    @Column(name = "failure_message", updatable = false, length = 500)
    private String failureMessage;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "started_at", updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    protected SimulationRun() {
    }

    public SimulationRun(UUID id, Instant now, UUID scenarioId, UUID factoryId, String idempotencyKey, Instant forkAt,
            String inputSnapshot, String inputHash, String baseStateHash, String algorithmVersion) {
        super(id, now);
        this.scenarioId = scenarioId;
        this.factoryId = factoryId;
        this.status = RunStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.forkAt = forkAt;
        this.inputSnapshot = inputSnapshot;
        this.inputHash = inputHash;
        this.baseStateHash = baseStateHash;
        this.algorithmVersion = algorithmVersion;
        this.submittedAt = now;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getForkAt() {
        return forkAt;
    }

    public String getInputSnapshot() {
        return inputSnapshot;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getBaseStateHash() {
        return baseStateHash;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public Instant getSimulatedEndAt() {
        return simulatedEndAt;
    }

    public Long getEventsProcessed() {
        return eventsProcessed;
    }

    public String getSummary() {
        return summary;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
