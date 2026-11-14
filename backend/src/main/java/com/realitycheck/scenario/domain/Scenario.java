package com.realitycheck.scenario.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A named "what if": a fork instant plus an ordered list of {@link ScenarioChange}s. The scenario
 * is the aggregate root for optimistic locking: editing its changes bumps its version.
 */
@Entity
@Table(name = "scenario")
public class Scenario extends BaseEntity {

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private String description;

    @Column(name = "parent_scenario_id", updatable = false)
    private UUID parentScenarioId;

    @Column(name = "fork_at", nullable = false)
    private Instant forkAt;

    @Column(name = "include_future_orders", nullable = false)
    private boolean includeFutureOrders;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Scenario() {
    }

    public Scenario(UUID id, Instant now, UUID factoryId, String name, String description, UUID parentScenarioId,
            Instant forkAt, boolean includeFutureOrders) {
        super(id, now);
        this.factoryId = factoryId;
        this.name = name;
        this.description = description;
        this.parentScenarioId = parentScenarioId;
        this.forkAt = forkAt;
        this.includeFutureOrders = includeFutureOrders;
        this.updatedAt = now;
    }

    public void update(String name, String description, Instant forkAt, boolean includeFutureOrders, Instant now) {
        this.name = name;
        this.description = description;
        this.forkAt = forkAt;
        this.includeFutureOrders = includeFutureOrders;
        this.updatedAt = now;
    }

    /** Marks the aggregate as changed, so its version moves when a change is added, edited or removed. */
    public void touch(Instant now) {
        this.updatedAt = now;
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

    public UUID getParentScenarioId() {
        return parentScenarioId;
    }

    public Instant getForkAt() {
        return forkAt;
    }

    public boolean isIncludeFutureOrders() {
        return includeFutureOrders;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
