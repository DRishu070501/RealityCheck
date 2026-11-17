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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /** Null when the workflow has none: every batch then visits every stage (spec 11). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<WorkflowRoute> routes;

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

    /** @return the routes, empty when the workflow has none */
    public List<WorkflowRoute> getRoutes() {
        return routes == null ? List.of() : List.copyOf(routes);
    }

    /**
     * Replaces the routes after checking them against the stages: unique codes, steps naming stages of this
     * workflow in ascending sequence, visit percents from 1 to 100, and first and last steps always visited.
     *
     * @throws IllegalArgumentException naming the first problem found
     */
    public void defineRoutes(List<WorkflowRoute> newRoutes) {
        Map<String, Integer> sequenceByCode = new HashMap<>();
        for (WorkflowStage stage : stages) {
            sequenceByCode.put(stage.getCode(), stage.getSequence());
        }
        Set<String> codes = new HashSet<>();
        for (WorkflowRoute route : newRoutes) {
            if (route.code() == null || route.code().isBlank() || route.code().length() > 40
                    || !codes.add(route.code())) {
                throw new IllegalArgumentException("Route codes must be present, unique and at most 40 characters");
            }
            if (route.steps() == null || route.steps().isEmpty()) {
                throw new IllegalArgumentException("Route " + route.code() + " has no steps");
            }
            int previous = 0;
            for (WorkflowRoute.Step step : route.steps()) {
                Integer sequence = sequenceByCode.get(step.stageCode());
                if (sequence == null) {
                    throw new IllegalArgumentException("Route " + route.code() + " names an unknown stage "
                            + step.stageCode());
                }
                if (sequence <= previous) {
                    throw new IllegalArgumentException("Route " + route.code() + " must list stages in sequence order");
                }
                if (step.visitPercent() < 1 || step.visitPercent() > 100) {
                    throw new IllegalArgumentException("Route " + route.code() + " visit percents must be 1 to 100");
                }
                previous = sequence;
            }
            if (route.steps().get(0).visitPercent() != 100
                    || route.steps().get(route.steps().size() - 1).visitPercent() != 100) {
                throw new IllegalArgumentException("Route " + route.code() + " must always visit its first and last steps");
            }
        }
        this.routes = newRoutes.isEmpty() ? null : List.copyOf(newRoutes);
    }
}
