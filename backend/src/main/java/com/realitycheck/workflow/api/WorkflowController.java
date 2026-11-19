package com.realitycheck.workflow.api;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.api.Paging;
import com.realitycheck.workflow.api.WorkflowDtos.ConfigureWorkflowRequest;
import com.realitycheck.workflow.api.WorkflowDtos.CreateWorkflowRequest;
import com.realitycheck.workflow.api.WorkflowDtos.RouteRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageBehaviourRequest;
import com.realitycheck.workflow.domain.WorkflowRoute;
import com.realitycheck.workflow.service.WorkflowService.StageBehaviour;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PutMapping;
import com.realitycheck.workflow.api.WorkflowDtos.StageResponse;
import com.realitycheck.workflow.api.WorkflowDtos.WorkflowResponse;
import com.realitycheck.workflow.api.WorkflowDtos.WorkflowSummaryResponse;
import com.realitycheck.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    @Operation(summary = "List a factory's workflows; the active one is first")
    public PageResponse<WorkflowSummaryResponse> list(
            @RequestParam(required = false) UUID factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return workflowService.list(factoryId, Paging.of(page, size, Sort.by("status", "name", "id")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A workflow with its stages in sequence order")
    public WorkflowResponse get(@PathVariable UUID id) {
        return workflowService.get(id);
    }

    @GetMapping("/{id}/stages")
    @Operation(summary = "The stages of a workflow in sequence order, with resource requirements and slot capacity")
    public PageResponse<StageResponse> stages(@PathVariable UUID id) {
        return workflowService.stages(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a workflow with all of its stages and their resource requirements")
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowResponse created = workflowService.create(request);
        return ResponseEntity.created(URI.create("/api/workflows/" + created.id())).body(created);
    }

    @PutMapping(path = "/{id}/configuration", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set stage kinds, measured delays and routes; meant for an import, before orders exist")
    public WorkflowResponse configure(@PathVariable UUID id, @Valid @RequestBody ConfigureWorkflowRequest request) {
        Map<String, StageBehaviour> behaviours = new LinkedHashMap<>();
        for (StageBehaviourRequest stage : request.stages() == null ? List.<StageBehaviourRequest>of()
                : request.stages()) {
            behaviours.put(stage.code(), new StageBehaviour(stage.kind(), stage.delayDeciles(),
                    stage.handlingDeciles()));
        }
        List<WorkflowRoute> routes = (request.routes() == null ? List.<RouteRequest>of() : request.routes()).stream()
                .map(r -> new WorkflowRoute(r.code(), r.name(), r.steps().stream()
                        .map(s -> new WorkflowRoute.Step(s.stageCode(), s.visitPercent())).toList()))
                .toList();
        workflowService.configure(id, behaviours, routes);
        return workflowService.get(id);
    }
}
