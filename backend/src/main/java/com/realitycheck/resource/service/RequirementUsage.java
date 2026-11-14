package com.realitycheck.resource.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which stages of ACTIVE workflows need a pool. Declared here and implemented by the workflow
 * module, so that {@code resource} does not depend on {@code workflow}.
 */
public interface RequirementUsage {

    record StageUsage(UUID stageId, String stageCode, String stageName, int sequence, int unitsPerSlot) {
    }

    /** @return usages by pool id, each list ordered by stage sequence; pools nothing requires are absent */
    Map<UUID, List<StageUsage>> stagesRequiring(Collection<UUID> resourceIds);
}
