package com.realitycheck.simulation.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.realitycheck.simulation.api.SimulationDtos.Bottleneck;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import com.realitycheck.simulation.api.SimulationDtos.Unfinished;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes of the stored-run API. Results carry {@code source: "SIMULATED"}. */
public final class RunDtos {

    private RunDtos() {
    }

    /** @param reused true when an existing run was returned instead of starting a new one */
    public record RunSubmittedResponse(UUID runId, UUID scenarioId, String status, boolean reused,
            Instant submittedAt) {
    }

    /**
     * @param inputHash      identifies the exact input: snapshot with changes, and algorithm version
     * @param baseStateHash  identifies the starting state before changes; runs are comparable when equal
     * @param failureMessage set when FAILED; never contains internals
     */
    public record RunResponse(UUID runId, UUID scenarioId, String scenarioName, UUID factoryId, String status,
            Instant forkAt, String algorithmVersion, String inputHash, String baseStateHash, String terminationReason,
            Instant simulatedEndAt, Long eventsProcessed, String failureMessage, Instant submittedAt,
            Instant startedAt, Instant finishedAt) {
    }

    public record RunResultsResponse(UUID runId, UUID scenarioId, String source, String algorithmVersion,
            String currencyCode, Instant forkAt, Instant simulatedEndAt, String terminationReason,
            long eventsProcessed, Summary summary, Bottleneck bottleneck, List<OrderResult> orders,
            List<PoolResult> resources, List<Unfinished> unfinished) {
    }

    /**
     * One entry of the simulated trace.
     *
     * @param eventType {@code JOB_QUEUED}, {@code JOB_STARTED}, {@code JOB_COMPLETED},
     *                  {@code CAPACITY_CHANGED} or {@code ORDER_COMPLETED}
     * @param stones    output for JOB_COMPLETED, input for JOB_QUEUED and JOB_STARTED
     * @param capacity  the pool's new capacity, for CAPACITY_CHANGED
     */
    public record SimulatedEventResponse(long seq, Instant occurredAt, String eventType, UUID batchId, UUID orderId,
            UUID stageId, UUID resourceId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal stones,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal carats,
            Integer capacity) {
    }
}
