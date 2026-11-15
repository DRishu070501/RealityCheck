package com.realitycheck.ai.service;

import com.realitycheck.ai.evidence.Evidence;
import com.realitycheck.ai.evidence.EvidenceBuilder;
import com.realitycheck.ai.evidence.RunInput;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.scenario.api.ComparisonDtos.CompareRequest;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.comparison.ComparisonService;
import com.realitycheck.scenario.snapshot.Snapshot.AppliedChange;
import com.realitycheck.simulation.api.RunDtos.RunResponse;
import com.realitycheck.simulation.api.RunDtos.RunResultsResponse;
import com.realitycheck.simulation.service.SimulationQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads stored results (and, with a baseline, the comparison) and builds the evidence. Read only:
 * nothing here can change a simulation result.
 */
@Service
public class EvidenceSource {

    private final SimulationQueryService runs;
    private final ComparisonService comparisons;

    public EvidenceSource(SimulationQueryService runs, ComparisonService comparisons) {
        this.runs = runs;
        this.comparisons = comparisons;
    }

    /**
     * @throws ApiException 404 for an unknown run, 409 for a run that is not COMPLETED, and the
     *                      comparison's 400 and 422 (different starting state) with a baseline
     */
    public Evidence load(UUID runId, UUID baselineRunIdOrNull) {
        RunInput run = input(runId);
        if (baselineRunIdOrNull == null) {
            return EvidenceBuilder.build(run, null, null);
        }
        RunInput baseline = input(baselineRunIdOrNull);
        ComparisonResponse comparison = comparisons.compare(
                new CompareRequest(baselineRunIdOrNull, List.of(runId), false, false));
        return EvidenceBuilder.build(run, baseline, comparison);
    }

    /** 404 when the run does not exist. */
    public void requireRun(UUID runId) {
        runs.run(runId);
    }

    private RunInput input(UUID runId) {
        RunResponse run = runs.run(runId);
        if (!"COMPLETED".equals(run.status())) {
            throw ApiException.conflict("Run " + runId + " is " + run.status()
                    + "; insights are available once it is COMPLETED.").withProperty("runStatus", run.status());
        }
        RunResultsResponse results = runs.results(runId);
        List<String> changes = runs.load(runId).orElseThrow().appliedChanges().stream()
                .map(AppliedChange::summary).toList();
        return new RunInput(runId, run.scenarioName(), results.algorithmVersion(), results.currencyCode(),
                results.forkAt(), results.terminationReason(), results.summary(), results.bottleneck(),
                runs.stages(runId), results.resources(), results.orders(), changes);
    }
}
