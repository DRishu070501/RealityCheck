package com.realitycheck.workflow.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workflow")
public class Workflow extends BaseEntity {

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkflowStatus status;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    private List<WorkflowStage> stages = new ArrayList<>();

    protected Workflow() {
    }

    public Workflow(UUID id, Instant now, UUID factoryId, String name, String description, WorkflowStatus status) {
        super(id, now);
        this.factoryId = factoryId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.updatedAt = now;
    }

    public void addStage(WorkflowStage stage) {
        stages.add(stage);
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Stages in sequence order, whether the workflow was just built or loaded. */
    public List<WorkflowStage> getStages() {
        return stages.stream().sorted(Comparator.comparingInt(WorkflowStage::getSequence)).toList();
    }
}
