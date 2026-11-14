package com.realitycheck.workflow.persistence;

import com.realitycheck.workflow.domain.StageRequirement;
import com.realitycheck.workflow.domain.WorkflowStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StageRequirementRepository extends JpaRepository<StageRequirement, UUID> {

    @Query("""
            select r from StageRequirement r
            join fetch r.stage s
            join s.workflow w
            where r.resourceId in :resourceIds and w.status = :status
            order by s.sequence, r.id""")
    List<StageRequirement> findByResourceIdsAndWorkflowStatus(Collection<UUID> resourceIds, WorkflowStatus status);
}
