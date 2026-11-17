package com.realitycheck.workflow.service;

import com.realitycheck.workflow.domain.WorkflowStage;
import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.persistence.WorkflowRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to a factory's active workflow for other modules, as plain records. */
@Service
@Transactional(readOnly = true)
public class WorkflowReader {

    /** Units as in {@code WorkflowStage}: rate per processing slot, setup per job, yields as fractions. */
    /**
     * @param kind         WORKING, PARKING, WAITING or JOB_WORK
     * @param delayDeciles ten calendar durations in seconds for a holding kind; null for WORKING
     * @param handlingDeciles ten calendar durations in seconds a batch stays after processing; null when none
     */
    public record StageView(UUID id, UUID workflowId, String code, String name, int sequence,
            BigDecimal stonesPerHour, long setupSeconds, BigDecimal stoneYield, BigDecimal caratYield,
            BigDecimal fixedCostPerJob, List<RequirementView> requirements, String kind, List<Long> delayDeciles,
            List<Long> handlingDeciles) {
    }

    public record RequirementView(UUID resourceId, int unitsPerSlot) {
    }

    /** A route of the active workflow (spec 11): its steps in stage sequence order. */
    public record RouteView(String code, String name, List<RouteStepView> steps) {
    }

    /** @param visitPercent below 100 for an optional step */
    public record RouteStepView(UUID stageId, String stageCode, int sequence, int visitPercent) {
    }

    private final WorkflowRepository workflows;

    public WorkflowReader(WorkflowRepository workflows) {
        this.workflows = workflows;
    }

    /** The active workflow's stages in sequence order and its routes, read together; both empty without one. */
    public record ActiveWorkflow(List<StageView> stages, List<RouteView> routes) {
    }

    public ActiveWorkflow activeWorkflow(UUID factoryId) {
        return workflows.findByFactoryIdAndStatus(factoryId, WorkflowStatus.ACTIVE)
                .map(workflow -> new ActiveWorkflow(stagesOf(workflow), routesOf(workflow)))
                .orElse(new ActiveWorkflow(List.of(), List.of()));
    }

    /** @return the stages of the factory's active workflow in sequence order; empty if it has none */
    public List<StageView> activeStages(UUID factoryId) {
        return workflows.findByFactoryIdAndStatus(factoryId, WorkflowStatus.ACTIVE)
                .map(WorkflowReader::stagesOf)
                .orElse(List.of());
    }

    private static List<StageView> stagesOf(com.realitycheck.workflow.domain.Workflow workflow) {
        return workflow.getStages().stream()
                        .map(stage -> new StageView(stage.getId(), workflow.getId(), stage.getCode(),
                                stage.getName(), stage.getSequence(), stage.getStonesPerHour(),
                                stage.getSetupSeconds(), stage.getStoneYield(), stage.getCaratYield(),
                                stage.getFixedCostPerJob(),
                                stage.getRequirements().stream()
                                        .map(r -> new RequirementView(r.getResourceId(), r.getUnitsPerSlot()))
                                        .sorted(Comparator.comparing(RequirementView::resourceId))
                                        .toList(),
                                stage.getKind(), stage.getDelayDeciles(), stage.getHandlingDeciles()))
                        .toList();
    }

    /** @return the routes of the factory's active workflow; empty if it has none */
    public List<RouteView> activeRoutes(UUID factoryId) {
        return workflows.findByFactoryIdAndStatus(factoryId, WorkflowStatus.ACTIVE)
                .map(WorkflowReader::routesOf)
                .orElse(List.of());
    }

    private static List<RouteView> routesOf(com.realitycheck.workflow.domain.Workflow workflow) {
        Map<String, WorkflowStage> byCode = workflow.getStages().stream()
                .collect(Collectors.toMap(WorkflowStage::getCode, Function.identity()));
        return workflow.getRoutes().stream()
                .map(route -> new RouteView(route.code(), route.name(), route.steps().stream()
                        .map(step -> {
                            WorkflowStage stage = byCode.get(step.stageCode());
                            return new RouteStepView(stage.getId(), stage.getCode(), stage.getSequence(),
                                    step.visitPercent());
                        })
                        .toList()))
                .toList();
    }
}
