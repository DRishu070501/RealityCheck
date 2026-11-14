package com.realitycheck.workflow.persistence;

import com.realitycheck.workflow.domain.Workflow;
import com.realitycheck.workflow.domain.WorkflowStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Page<Workflow> findByFactoryId(UUID factoryId, Pageable pageable);

    Optional<Workflow> findByFactoryIdAndStatus(UUID factoryId, WorkflowStatus status);

    boolean existsByFactoryIdAndStatus(UUID factoryId, WorkflowStatus status);

    boolean existsByFactoryIdAndNameIgnoreCase(UUID factoryId, String name);

    long countByFactoryId(UUID factoryId);
}
