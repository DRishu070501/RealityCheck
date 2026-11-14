package com.realitycheck.simulation.service;

import com.realitycheck.scenario.snapshot.SimulationEngineContract;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BottleneckConfigSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.LimitsSnapshot;
import com.realitycheck.simulation.engine.SimulationEngine;
import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.SimulationLimits;
import java.util.List;
import org.springframework.stereotype.Component;

/** The engine's version, defaults and input validation, offered to the scenario module. */
@Component
class EngineContractAdapter implements SimulationEngineContract {

    private final SimulationEngine engine = new SimulationEngine();

    @Override
    public String algorithmVersion() {
        return SimulationEngine.ALGORITHM_VERSION;
    }

    @Override
    public LimitsSnapshot defaultLimits() {
        SimulationLimits limits = SimulationLimits.defaults();
        return new LimitsSnapshot(limits.maxSimulatedSeconds(), limits.maxEvents());
    }

    @Override
    public BottleneckConfigSnapshot defaultBottleneck() {
        BottleneckConfig c = BottleneckConfig.defaults();
        return new BottleneckConfigSnapshot(c.utilizationWeight(), c.waitWeight(), c.queueWeight(),
                c.saturationWeight(), c.scoreThreshold(), c.saturationThreshold());
    }

    @Override
    public List<EngineProblem> check(Snapshot snapshot) {
        return engine.problems(SnapshotInputAdapter.toInput(snapshot)).stream()
                .map(p -> new EngineProblem(p.code(), p.message(), p.subject()))
                .toList();
    }
}
