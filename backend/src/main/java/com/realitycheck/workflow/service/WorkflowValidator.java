package com.realitycheck.workflow.service;

import com.realitycheck.common.error.FieldViolation;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.api.WorkflowDtos.CreateStageRequest;
import com.realitycheck.workflow.api.WorkflowDtos.StageRequirementRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Workflow rules that span stages or reach into other data: sequences are exactly 1..N, codes are
 * unique, and each requirement names an active pool of the same factory, once per stage.
 * Pure, so it can be tested without a database.
 */
public final class WorkflowValidator {

    private WorkflowValidator() {
    }

    /**
     * @param pools the pools that exist among the ids the request references, keyed by id
     */
    public static List<FieldViolation> validate(UUID factoryId, List<CreateStageRequest> stages,
            Map<UUID, ResourceView> pools) {
        List<FieldViolation> errors = new ArrayList<>();

        Set<Integer> sequences = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            CreateStageRequest stage = stages.get(i);
            String path = "stages[" + i + "]";
            if (!sequences.add(stage.sequence())) {
                errors.add(new FieldViolation(path + ".sequence", "is used by more than one stage"));
            } else if (stage.sequence() > stages.size()) {
                errors.add(new FieldViolation(path + ".sequence",
                        "must be between 1 and " + stages.size() + " with no gaps"));
            }
            if (!codes.add(stage.code())) {
                errors.add(new FieldViolation(path + ".code", "is used by more than one stage"));
            }
            validateRequirements(factoryId, stage, path, pools, errors);
        }
        return errors;
    }

    private static void validateRequirements(UUID factoryId, CreateStageRequest stage, String stagePath,
            Map<UUID, ResourceView> pools, List<FieldViolation> errors) {
        Set<UUID> seen = new HashSet<>();
        List<StageRequirementRequest> requirements = stage.requirementsOrEmpty();
        for (int i = 0; i < requirements.size(); i++) {
            StageRequirementRequest requirement = requirements.get(i);
            String path = stagePath + ".requirements[" + i + "].resourceId";
            ResourceView pool = pools.get(requirement.resourceId());
            if (!seen.add(requirement.resourceId())) {
                errors.add(new FieldViolation(path, "is listed more than once for this stage"));
            } else if (pool == null) {
                errors.add(new FieldViolation(path, "does not exist"));
            } else if (!pool.factoryId().equals(factoryId)) {
                errors.add(new FieldViolation(path, "belongs to a different factory"));
            } else if (pool.status() != ResourceStatus.ACTIVE) {
                errors.add(new FieldViolation(path, "is not active"));
            }
        }
    }

    /**
     * @return the number of jobs a stage can run at once given each requirement's units per slot
     *         and pool capacity, or null when there are no requirements (unlimited)
     */
    public static Integer maxSlots(List<int[]> unitsPerSlotAndCapacity) {
        Integer slots = null;
        for (int[] pair : unitsPerSlotAndCapacity) {
            int possible = pair[1] / pair[0];
            slots = slots == null ? possible : Math.min(slots, possible);
        }
        return slots;
    }
}
