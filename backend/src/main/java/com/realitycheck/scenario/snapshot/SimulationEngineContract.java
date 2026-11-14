package com.realitycheck.scenario.snapshot;

import com.realitycheck.scenario.snapshot.Snapshot.BottleneckConfigSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.LimitsSnapshot;
import java.util.List;

/**
 * What the scenario module needs to know about the simulation engine, declared here and
 * implemented in {@code simulation}, so the dependency runs {@code simulation -> scenario} as
 * architecture.md requires and the engine's rules are never duplicated.
 */
public interface SimulationEngineContract {

    /** Part of every input hash: the same input under a different algorithm is a different run. */
    String algorithmVersion();

    LimitsSnapshot defaultLimits();

    BottleneckConfigSnapshot defaultBottleneck();

    /** The engine's own input validation, run on the snapshot; empty when it can be simulated. */
    List<EngineProblem> check(Snapshot snapshot);

    /**
     * @param code    stable identifier, for example {@code REQUIREMENT_EXCEEDS_CAPACITY}
     * @param subject what it is about (a stage code, a pool name); may be null
     */
    record EngineProblem(String code, String message, String subject) {
    }
}
