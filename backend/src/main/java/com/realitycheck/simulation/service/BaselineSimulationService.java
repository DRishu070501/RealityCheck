package com.realitycheck.simulation.service;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.scenario.service.SnapshotAssembler;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.engine.InvalidSimulationInputException;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the engine on the factory as recorded, from a chosen instant, and returns the result
 * without storing anything. It answers "what happens if nothing changes?" ad hoc; stored,
 * comparable runs belong to scenarios.
 */
@Service
public class BaselineSimulationService {

    private static final Logger log = LoggerFactory.getLogger(BaselineSimulationService.class);

    private final SnapshotAssembler assembler;
    private final Clock clock;
    private final SimulationEngine engine = new SimulationEngine();

    public BaselineSimulationService(SnapshotAssembler assembler, Clock clock) {
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * @param atOrNull the instant to simulate from; now when null. Not in the future: there is no
     *                 recorded state to start from.
     */
    @Transactional(readOnly = true)
    public SimulationResultResponse simulate(UUID factoryId, Instant atOrNull) {
        Instant now = clock.instant();
        Instant at = atOrNull == null ? now : atOrNull;
        if (at.isAfter(now)) {
            throw new ValidationException("at", "must not be in the future");
        }
        Snapshot snapshot = assembler.build(factoryId, at, true);
        SimulationResult result;
        try {
            result = engine.run(SnapshotInputAdapter.toInput(snapshot));
        } catch (InvalidSimulationInputException ex) {
            // The messages are written for users and name only stages, pools and orders.
            throw ApiException.unprocessable("The factory cannot be simulated as configured: " + ex.getMessage());
        }
        log.info("Baseline simulation factoryId={} forkAt={} batches={} events={} termination={}", factoryId, at,
                snapshot.batches().size(), result.eventsProcessed(), result.terminationReason());
        return SimulationResultMapper.toResponse(factoryId, snapshot, result);
    }
}
