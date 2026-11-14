package com.realitycheck.production.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A customer commitment: what was ordered, how urgent it is and when it is due. */
@Entity
@Table(name = "production_order")
public class ProductionOrder extends BaseEntity {

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 40)
    private String orderNumber;

    @Column(name = "customer_ref", length = 120)
    private String customerRef;

    /** 1 to 5; 5 is the most urgent. */
    @Column(nullable = false)
    private int priority;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductionOrder() {
    }

    public ProductionOrder(UUID id, Instant recordedAt, UUID factoryId, String orderNumber, String customerRef,
            int priority, Instant dueAt, Instant placedAt) {
        super(id, recordedAt);
        this.factoryId = factoryId;
        this.orderNumber = orderNumber;
        this.customerRef = customerRef;
        this.priority = priority;
        this.dueAt = dueAt;
        this.placedAt = placedAt;
        this.status = OrderStatus.PENDING;
        this.updatedAt = recordedAt;
    }

    /** Stores what the event history says about this order. */
    public void project(OrderStatus status, Instant completedAt, Instant recordedAt) {
        if (this.status != status || !java.util.Objects.equals(this.completedAt, completedAt)) {
            this.status = status;
            this.completedAt = completedAt;
            this.updatedAt = recordedAt;
        }
    }

    /** Late if it finished after it was due, or is unfinished and already past due at {@code asOf}. */
    public boolean isDelayed(Instant asOf) {
        if (dueAt == null) {
            return false;
        }
        return completedAt != null ? completedAt.isAfter(dueAt) : asOf.isAfter(dueAt);
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
