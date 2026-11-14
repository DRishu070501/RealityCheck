package com.realitycheck.simulation.api;

import com.realitycheck.simulation.api.SimulationDtos.SimulationResultResponse;
import com.realitycheck.simulation.service.BaselineSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/factories/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulation")
public class BaselineSimulationController {

    private final BaselineSimulationService baselineSimulationService;

    public BaselineSimulationController(BaselineSimulationService baselineSimulationService) {
        this.baselineSimulationService = baselineSimulationService;
    }

    @GetMapping("/baseline-simulation")
    @Operation(summary = "Simulate the factory as recorded from an instant, changing nothing and storing nothing")
    public SimulationResultResponse baseline(@PathVariable UUID id, @RequestParam(required = false) Instant at) {
        return baselineSimulationService.simulate(id, at);
    }
}
