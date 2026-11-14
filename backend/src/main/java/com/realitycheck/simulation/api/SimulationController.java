package com.realitycheck.simulation.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.simulation.api.RunDtos.RunResponse;
import com.realitycheck.simulation.api.RunDtos.RunResultsResponse;
import com.realitycheck.simulation.api.RunDtos.RunSubmittedResponse;
import com.realitycheck.simulation.api.RunDtos.SimulatedEventResponse;
import com.realitycheck.simulation.api.SimulationDtos.PoolResult;
import com.realitycheck.simulation.api.SimulationDtos.StageResult;
import com.realitycheck.simulation.service.SimulationQueryService;
import com.realitycheck.simulation.service.SimulationSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulation")
public class SimulationController {

    private final SimulationSubmissionService submissionService;
    private final SimulationQueryService queryService;

    public SimulationController(SimulationSubmissionService submissionService, SimulationQueryService queryService) {
        this.submissionService = submissionService;
        this.queryService = queryService;
    }

    @PostMapping("/scenarios/{id}/simulate")
    @Operation(summary = "Start a simulation of the scenario in the background; returns the run id at once."
            + " An identical completed run is reused unless force=true")
    public ResponseEntity<RunSubmittedResponse> simulate(@PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RunSubmittedResponse submitted = submissionService.submit(id, idempotencyKey, force);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/simulations/" + submitted.runId()))
                .body(submitted);
    }

    @GetMapping("/scenarios/{id}/simulations")
    @Operation(summary = "A scenario's runs, newest first")
    public PageResponse<RunResponse> runs(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return queryService.runsOfScenario(id, Paging.of(page, size, SimulationQueryService.NEWEST_FIRST));
    }

    @GetMapping("/simulations/{runId}")
    @Operation(summary = "A run's status, timing, hashes and termination reason")
    public RunResponse run(@PathVariable UUID runId) {
        return queryService.run(runId);
    }

    @GetMapping("/simulations/{runId}/results")
    @Operation(summary = "Summary metrics, order outcomes, resources and bottleneck of a completed run; 409 before")
    public RunResultsResponse results(@PathVariable UUID runId) {
        return queryService.results(runId);
    }

    @GetMapping("/simulations/{runId}/stages")
    @Operation(summary = "Per-stage results with bottleneck evidence, in sequence order")
    public List<StageResult> stages(@PathVariable UUID runId) {
        return queryService.stages(runId);
    }

    @GetMapping("/simulations/{runId}/resources")
    @Operation(summary = "Per-pool utilization and cost, machines first")
    public List<PoolResult> resources(@PathVariable UUID runId) {
        return queryService.resources(runId);
    }

    @GetMapping("/simulations/{runId}/events")
    @Operation(summary = "The simulated event trace in order, optionally for one stage or order")
    public PageResponse<SimulatedEventResponse> events(@PathVariable UUID runId,
            @RequestParam(required = false) UUID stageId, @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return queryService.events(runId, stageId, orderId, Paging.of(page, size, Sort.unsorted()));
    }
}
