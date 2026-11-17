package com.realitycheck.workflow.service;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryCountProvider;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.api.WorkflowDtos.CreateStageRequest;
import com.realitycheck.workflow.api.WorkflowDtos.CreateWorkflowRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementResponse;
import com.realitycheck.workflow.api.WorkflowDtos.StageResponse;
import com.realitycheck.workflow.api.WorkflowDtos.WorkflowResponse;
import com.realitycheck.workflow.api.WorkflowDtos.WorkflowSummaryResponse;
import com.realitycheck.workflow.domain.StageRequirement;
import com.realitycheck.workflow.domain.Workflow;
import com.realitycheck.workflow.domain.WorkflowRoute;
import com.realitycheck.workflow.domain.WorkflowStage;
import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.persistence.WorkflowRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService implements FactoryCountProvider {

    private final WorkflowRepository workflows;
    private final FactoryResolver factoryResolver;
    private final ResourceReader resourceReader;
    private final Clock clock;

    public WorkflowService(WorkflowRepository workflows, FactoryResolver factoryResolver,
            ResourceReader resourceReader, Clock clock) {
        this.workflows = workflows;
        this.factoryResolver = factoryResolver;
        this.resourceReader = resourceReader;
        this.clock = clock;
    }

    @Transactional
    public WorkflowResponse create(CreateWorkflowRequest request) {
        return create(request, Ids.random(), clock.instant());
    }

    /** Creation with a caller-chosen id and timestamp; the demonstration seed uses fixed values. */
    @Transactional
    public WorkflowResponse create(CreateWorkflowRequest request, UUID id, Instant now) {
        UUID factoryId = factoryResolver.resolve(request.factoryId());
        Map<UUID, ResourceView> pools = resourceReader.byIds(request.stages().stream()
                .flatMap(stage -> stage.requirementsOrEmpty().stream())
                .map(StageRequirementRequest::resourceId)
                .distinct()
                .toList());
        List<FieldViolation> errors = WorkflowValidator.validate(factoryId, request.stages(), pools);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String name = request.name().strip();
        if (workflows.existsByFactoryIdAndNameIgnoreCase(factoryId, name)) {
            throw ApiException.conflict("A workflow named \"" + name + "\" already exists in this factory");
        }
        if (request.status() == WorkflowStatus.ACTIVE
                && workflows.existsByFactoryIdAndStatus(factoryId, WorkflowStatus.ACTIVE)) {
            throw ApiException.conflict("This factory already has an active workflow");
        }

        Workflow workflow = new Workflow(id, now, factoryId, name, blankToNull(request.description()),
                request.status());
        for (CreateStageRequest s : request.stages()) {
            WorkflowStage stage = new WorkflowStage(workflow, s.code(), s.name().strip(),
                    blankToNull(s.description()), s.sequence(),
                    s.stonesPerHour().setScale(4, RoundingMode.UNNECESSARY), s.setupSeconds(),
                    s.stoneYield().setScale(6, RoundingMode.UNNECESSARY),
                    s.caratYield().setScale(6, RoundingMode.UNNECESSARY),
                    s.fixedCostPerJob().setScale(2, RoundingMode.UNNECESSARY));
            for (StageRequirementRequest requirement : s.requirementsOrEmpty()) {
                stage.addRequirement(requirement.resourceId(), requirement.unitsPerSlot());
            }
            workflow.addStage(stage);
        }
        return toResponse(workflows.saveAndFlush(workflow), pools);
    }

    /**
     * How one stage behaves beyond its rate and pools (spec 11): its kind and measured delays.
     *
     * @param kind            WORKING, PARKING, WAITING or JOB_WORK
     * @param delayDeciles    for a holding kind: ten ascending calendar durations in seconds; null for WORKING
     * @param handlingDeciles for WORKING, optional: ten ascending durations a batch stays after processing
     */
    public record StageBehaviour(String kind, List<Long> delayDeciles, List<Long> handlingDeciles) {
    }

    /**
     * Sets stage kinds, delays and the workflow's routes. Not exposed by the API in this release: the
     * demonstration seed and imports configure them.
     *
     * @param behaviours by stage code; stages not named stay as they are
     * @throws ValidationException when a stage or route does not fit the workflow
     */
    @Transactional
    public void configure(UUID workflowId, Map<String, StageBehaviour> behaviours, List<WorkflowRoute> routes) {
        Workflow workflow = workflows.findById(workflowId)
                .orElseThrow(() -> ApiException.notFound("Workflow", workflowId));
        try {
            for (WorkflowStage stage : workflow.getStages()) {
                StageBehaviour behaviour = behaviours.get(stage.getCode());
                if (behaviour == null) {
                    continue;
                }
                if ("WORKING".equals(behaviour.kind())) {
                    if (behaviour.handlingDeciles() != null) {
                        stage.handleFor(behaviour.handlingDeciles());
                    }
                } else {
                    stage.holdFor(behaviour.kind(), behaviour.delayDeciles());
                }
            }
            workflow.defineRoutes(routes);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ValidationException("routes", ex.getMessage());
        }
        workflows.saveAndFlush(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(UUID id) {
        Workflow workflow = workflows.findById(id).orElseThrow(() -> ApiException.notFound("Workflow", id));
        return toResponse(workflow, poolsOf(workflow));
    }

    @Transactional(readOnly = true)
    public PageResponse<StageResponse> stages(UUID workflowId) {
        List<StageResponse> stages = get(workflowId).stages();
        return PageResponse.of(stages, 0, Math.max(1, stages.size()), stages.size());
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowSummaryResponse> list(UUID factoryIdOrNull, Pageable pageable) {
        UUID factoryId = factoryResolver.resolve(factoryIdOrNull);
        return PageResponse.from(workflows.findByFactoryId(factoryId, pageable), w -> new WorkflowSummaryResponse(
                w.getId(), w.getFactoryId(), w.getName(), w.getStatus(), w.getStages().size()));
    }

    @Override
    public String key() {
        return "workflows";
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID factoryId) {
        return workflows.countByFactoryId(factoryId);
    }

    private Map<UUID, ResourceView> poolsOf(Workflow workflow) {
        return resourceReader.byIds(workflow.getStages().stream()
                .flatMap(stage -> stage.getRequirements().stream())
                .map(StageRequirement::getResourceId)
                .distinct()
                .toList());
    }

    private static WorkflowResponse toResponse(Workflow workflow, Map<UUID, ResourceView> pools) {
        List<StageResponse> stages = workflow.getStages().stream().map(stage -> toResponse(stage, pools)).toList();
        return new WorkflowResponse(workflow.getId(), workflow.getFactoryId(), workflow.getName(),
                workflow.getDescription(), workflow.getStatus(), stages, workflow.getVersion(),
                workflow.getCreatedAt(), workflow.getUpdatedAt());
    }

    private static StageResponse toResponse(WorkflowStage stage, Map<UUID, ResourceView> pools) {
        List<StageRequirementResponse> requirements = stage.getRequirements().stream()
                .map(r -> {
                    ResourceView pool = pools.get(r.getResourceId());
                    return new StageRequirementResponse(r.getResourceId(), pool.name(), pool.kind(),
                            r.getUnitsPerSlot(), pool.capacity());
                })
                .sorted(Comparator.comparing(StageRequirementResponse::kind)
                        .thenComparing(StageRequirementResponse::resourceName)
                        .thenComparing(StageRequirementResponse::resourceId))
                .toList();
        Integer maxSlots = WorkflowValidator.maxSlots(requirements.stream()
                .map(r -> new int[] {r.unitsPerSlot(), r.poolCapacity()})
                .toList());
        return new StageResponse(stage.getId(), stage.getCode(), stage.getName(), stage.getDescription(),
                stage.getSequence(), stage.getStonesPerHour(), stage.getSetupSeconds(), stage.getStoneYield(),
                stage.getCaratYield(), stage.getFixedCostPerJob(), requirements, maxSlots, stage.getKind(),
                stage.getDelayDeciles(), stage.getHandlingDeciles());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
