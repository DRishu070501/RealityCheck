package com.realitycheck.workflow.service;

import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.persistence.WorkflowRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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

    private final WorkflowRepository workflows;

    public WorkflowReader(WorkflowRepository workflows) {
        this.workflows = workflows;
    }

    /** @return the stages of the factory's active workflow in sequence order; empty if it has none */
    public List<StageView> activeStages(UUID factoryId) {
        return workflows.findByFactoryIdAndStatus(factoryId, WorkflowStatus.ACTIVE)
                .map(workflow -> workflow.getStages().stream()
                        .map(stage -> new StageView(stage.getId(), workflow.getId(), stage.getCode(),
                                stage.getName(), stage.getSequence(), stage.getStonesPerHour(),
                                stage.getSetupSeconds(), stage.getStoneYield(), stage.getCaratYield(),
                                stage.getFixedCostPerJob(),
                                stage.getRequirements().stream()
                                        .map(r -> new RequirementView(r.getResourceId(), r.getUnitsPerSlot()))
                                        .sorted(Comparator.comparing(RequirementView::resourceId))
                                        .toList(),
                                stage.getKind(), stage.getDelayDeciles(), stage.getHandlingDeciles()))
                        .toList())
                .orElse(List.of());
    }
}
