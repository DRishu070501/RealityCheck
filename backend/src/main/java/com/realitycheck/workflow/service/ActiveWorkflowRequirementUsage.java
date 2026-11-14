package com.realitycheck.workflow.service;

import com.realitycheck.resource.service.RequirementUsage;
import com.realitycheck.workflow.domain.StageRequirement;
import com.realitycheck.workflow.domain.WorkflowStage;
import com.realitycheck.workflow.domain.WorkflowStatus;
import com.realitycheck.workflow.persistence.StageRequirementRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the resource module which stages of active workflows need each pool. */
@Component
public class ActiveWorkflowRequirementUsage implements RequirementUsage {

    private final StageRequirementRepository requirements;

    public ActiveWorkflowRequirementUsage(StageRequirementRepository requirements) {
        this.requirements = requirements;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<StageUsage>> stagesRequiring(Collection<UUID> resourceIds) {
        Map<UUID, List<StageUsage>> usages = new LinkedHashMap<>();
        if (resourceIds.isEmpty()) {
            return usages;
        }
        for (StageRequirement requirement
                : requirements.findByResourceIdsAndWorkflowStatus(resourceIds, WorkflowStatus.ACTIVE)) {
            WorkflowStage stage = requirement.getStage();
            usages.computeIfAbsent(requirement.getResourceId(), id -> new ArrayList<>())
                    .add(new StageUsage(stage.getId(), stage.getCode(), stage.getName(), stage.getSequence(),
                            requirement.getUnitsPerSlot()));
        }
        return usages;
    }
}
