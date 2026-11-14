package com.realitycheck.simulation.service;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.engine.InvalidSimulationInputException;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationInput;
import com.realitycheck.simulation.engine.SimulationResult;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the engine on the factory as recorded, from a chosen instant, and returns the result
 * without storing anything. It answers "what happens if nothing changes?", which is the baseline
 * that scenarios are compared with.
 */
@Service
public class BaselineSimulationService {

    private static final Logger log = LoggerFactory.getLogger(BaselineSimulationService.class);

    private final SimulationInputAssembler assembler;
    private final FactoryService factoryService;
    private final WorkflowReader workflowReader;
    private final Clock clock;
    private final SimulationEngine engine = new SimulationEngine();

    public BaselineSimulationService(SimulationInputAssembler assembler, FactoryService factoryService,
            WorkflowReader workflowReader, Clock clock) {
        this.assembler = assembler;
        this.factoryService = factoryService;
        this.workflowReader = workflowReader;
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
        String currency = factoryService.get(factoryId).currencyCode();
        SimulationInput input = assembler.baseline(factoryId, at);
        SimulationResult result;
        try {
            result = engine.run(input);
        } catch (InvalidSimulationInputException ex) {
            // The messages are written for users and name only stages, pools and orders.
            throw ApiException.unprocessable("The factory cannot be simulated as configured: " + ex.getMessage());
        }
        log.info("Baseline simulation factoryId={} forkAt={} batches={} events={} termination={}", factoryId, at,
                input.batches().size(), result.eventsProcessed(), result.terminationReason());
        Map<UUID, String> stageNames = workflowReader.activeStages(factoryId).stream()
                .collect(Collectors.toMap(StageView::id, StageView::name));
        return SimulationResultMapper.toResponse(factoryId, currency, stageNames, result);
    }
}
