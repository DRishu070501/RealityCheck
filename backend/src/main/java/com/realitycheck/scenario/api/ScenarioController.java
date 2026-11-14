package com.realitycheck.scenario.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.scenario.api.ScenarioDtos.CreateScenarioRequest;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioChangeRequest;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioChangeResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioSummaryResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioValidationResponse;
import com.realitycheck.scenario.api.ScenarioDtos.UpdateChangeRequest;
import com.realitycheck.scenario.api.ScenarioDtos.UpdateScenarioRequest;
import com.realitycheck.scenario.service.ScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Scenarios")
public class ScenarioController {

    private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("name"),
            Sort.Order.asc("id"));

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    @Operation(summary = "List a factory's scenarios with their change count and latest run")
    public PageResponse<ScenarioSummaryResponse> list(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return scenarioService.list(factoryId, Paging.of(page, size, OLDEST_FIRST));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A scenario and its changes in order")
    public ScenarioResponse get(@PathVariable UUID id) {
        return scenarioService.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a scenario from an instant of recorded history, optionally branching from another")
    public ResponseEntity<ScenarioResponse> create(@Valid @RequestBody CreateScenarioRequest request) {
        ScenarioResponse created = scenarioService.create(request);
        return ResponseEntity.created(URI.create("/api/scenarios/" + created.id())).body(created);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename or describe a scenario, or move its fork while it has no runs; 409 if the version is stale")
    public ScenarioResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateScenarioRequest request) {
        return scenarioService.update(id, request);
    }

    @PostMapping(path = "/{id}/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Append a change; the payload is validated against its type and the factory")
    public ResponseEntity<ScenarioChangeResponse> addChange(@PathVariable UUID id,
            @Valid @RequestBody ScenarioChangeRequest request) {
        return ResponseEntity.status(201).body(scenarioService.addChange(id, request));
    }

    @PutMapping(path = "/{id}/changes/{changeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace a change's payload; its type cannot change")
    public ScenarioChangeResponse replaceChange(@PathVariable UUID id, @PathVariable UUID changeId,
            @Valid @RequestBody UpdateChangeRequest request) {
        return scenarioService.replaceChange(id, changeId, request);
    }

    @DeleteMapping("/{id}/changes/{changeId}")
    @Operation(summary = "Remove a change; later changes are renumbered without gaps")
    public ResponseEntity<Void> deleteChange(@PathVariable UUID id, @PathVariable UUID changeId) {
        scenarioService.deleteChange(id, changeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate")
    @Operation(summary = "Check whether the scenario can be simulated and preview its configuration; stores nothing")
    public ScenarioValidationResponse validate(@PathVariable UUID id) {
        return scenarioService.validate(id);
    }
}
