package com.realitycheck.resource.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A dated change to a pool's capacity over the half-open window {@code [startsAt, endsAt)}.
 * A negative delta is units unavailable. Rows are never updated.
 */
@Entity
@Table(name = "resource_availability")
public class ResourceAvailability extends BaseEntity {

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Column(name = "capacity_delta", nullable = false, updatable = false)
    private int capacityDelta;

    @Column(nullable = false, updatable = false, length = 200)
    private String reason;

    protected ResourceAvailability() {
    }

    public ResourceAvailability(UUID id, Instant now, UUID resourceId, Instant startsAt, Instant endsAt,
            int capacityDelta, String reason) {
        super(id, now);
        this.resourceId = resourceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacityDelta = capacityDelta;
        this.reason = reason;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getCapacityDelta() {
        return capacityDelta;
    }

    public String getReason() {
        return reason;
    }
}
