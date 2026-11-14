package com.realitycheck.resource.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A pool of interchangeable units: machines of one type or employees with one skill.
 * {@code capacity} is the number of units; {@code hourlyCost} is per unit per hour.
 */
@Entity
@Table(name = "resource")
public class Resource extends BaseEntity {

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ResourceKind kind;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "hourly_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal hourlyCost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResourceStatus status;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Resource() {
    }

    public Resource(UUID id, Instant now, UUID factoryId, ResourceKind kind, String category, String name,
            int capacity, BigDecimal hourlyCost) {
        super(id, now);
        this.factoryId = factoryId;
        this.kind = kind;
        this.category = category;
        this.name = name;
        this.capacity = capacity;
        this.hourlyCost = hourlyCost;
        this.status = ResourceStatus.ACTIVE;
        this.updatedAt = now;
    }

    /** The kind and factory of a pool never change; everything else may. */
    public void update(String name, String category, int capacity, BigDecimal hourlyCost, ResourceStatus status,
            Instant now) {
        this.name = name;
        this.category = category;
        this.capacity = capacity;
        this.hourlyCost = hourlyCost;
        this.status = status;
        this.updatedAt = now;
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public ResourceKind getKind() {
        return kind;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public int getCapacity() {
        return capacity;
    }

    public BigDecimal getHourlyCost() {
        return hourlyCost;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
