package com.realitycheck.scenario.api;

import com.realitycheck.scenario.api.ComparisonDtos.CompareRequest;
import com.realitycheck.scenario.api.ComparisonDtos.ComparisonResponse;
import com.realitycheck.scenario.api.ComparisonDtos.ScenarioComparisonsResponse;
import com.realitycheck.scenario.comparison.ComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Comparison")
public class ComparisonController {

    private final ComparisonService comparisonService;

    public ComparisonController(ComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @PostMapping(path = "/simulations/compare", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Compare a baseline run with up to five alternatives that started from the same state")
    public ComparisonResponse compare(@Valid @RequestBody CompareRequest request) {
        return comparisonService.compare(request);
    }

    @GetMapping("/scenarios/{id}/comparisons")
    @Operation(summary = "The scenario's latest completed run compared with each matching baseline run")
    public ScenarioComparisonsResponse forScenario(@PathVariable UUID id) {
        return comparisonService.forScenario(id);
    }
}
