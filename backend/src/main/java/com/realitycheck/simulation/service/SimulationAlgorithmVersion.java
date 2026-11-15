package com.realitycheck.simulation.service;

import com.realitycheck.common.system.AlgorithmVersionSource;
import com.realitycheck.simulation.engine.SimulationEngine;
import org.springframework.stereotype.Component;

/** Reports the engine's algorithm version, the one every new run records. */
@Component
class SimulationAlgorithmVersion implements AlgorithmVersionSource {

    @Override
    public String algorithmVersion() {
        return SimulationEngine.ALGORITHM_VERSION;
    }
}
