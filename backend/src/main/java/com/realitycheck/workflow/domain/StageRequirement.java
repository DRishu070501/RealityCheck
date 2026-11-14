package com.realitycheck.workflow.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One processing slot of the stage needs {@code unitsPerSlot} units of the referenced pool. */
@Entity
@Table(name = "stage_requirement")
public class StageRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private WorkflowStage stage;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "units_per_slot", nullable = false)
    private int unitsPerSlot;

    protected StageRequirement() {
    }

    StageRequirement(UUID id, Instant now, WorkflowStage stage, UUID resourceId, int unitsPerSlot) {
        super(id, now);
        this.stage = stage;
        this.resourceId = resourceId;
        this.unitsPerSlot = unitsPerSlot;
    }

    public WorkflowStage getStage() {
        return stage;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public int getUnitsPerSlot() {
        return unitsPerSlot;
    }
}
