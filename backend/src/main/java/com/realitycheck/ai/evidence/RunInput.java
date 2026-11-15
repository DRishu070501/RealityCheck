package com.realitycheck.ai.evidence;

import com.realitycheck.simulation.api.SimulationDtos.Bottleneck;
import com.realitycheck.simulation.api.SimulationDtos.OrderResult;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.api.SimulationDtos.Summary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One completed run's stored results, as read for the evidence document. */
public record RunInput(UUID runId, String scenarioName, String algorithmVersion, String currencyCode,
        Instant forkAt, String terminationReason, Summary summary, Bottleneck bottleneck, List<StageResult> stages,
        List<PoolResult> pools, List<OrderResult> orders, List<String> appliedChanges) {

    public RunInput {
        stages = List.copyOf(stages);
        pools = List.copyOf(pools);
        orders = List.copyOf(orders);
        appliedChanges = List.copyOf(appliedChanges);
    }
}
