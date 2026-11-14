package com.realitycheck.scenario.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What the scenario module needs to know about simulation runs. Declared here and implemented in
 * {@code simulation}, so the module dependency runs {@code simulation -> scenario}.
 */
public interface ScenarioRunLookup {

    boolean hasRuns(UUID scenarioId);

    /** @return the newest run of each scenario that has one, keyed by scenario id */
    Map<UUID, LatestRun> latestRuns(Collection<UUID> scenarioIds);

    /** @param finishedAt null while pending or running */
    record LatestRun(UUID runId, String status, Instant finishedAt) {
    }
}
