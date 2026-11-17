package com.realitycheck.production.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A parcel of stones moving through the workflow. {@code stoneCount} and {@code roughCarats} are
 * what it started with; the {@code current*} fields are projections of its event history.
 */
@Entity
@Table(name = "production_batch")
public class ProductionBatch extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "batch_number", nullable = false, updatable = false)
    private int batchNumber;

    @Column(name = "stone_count", nullable = false, updatable = false)
    private int stoneCount;

    @Column(name = "rough_carats", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal roughCarats;

    @Column(name = "current_stones", nullable = false, precision = 14, scale = 4)
    private BigDecimal currentStones;

    @Column(name = "current_carats", nullable = false, precision = 14, scale = 4)
    private BigDecimal currentCarats;

    @Column(name = "current_stage_id")
    private UUID currentStageId;

    /** The workflow route it follows (spec 11); null to visit every stage in sequence. */
    @Column(name = "route_code", updatable = false, length = 40)
    private String routeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductionBatch() {
    }

    public ProductionBatch(UUID id, Instant recordedAt, UUID orderId, int batchNumber, int stoneCount,
            BigDecimal roughCarats, UUID firstStageId) {
        this(id, recordedAt, orderId, batchNumber, stoneCount, roughCarats, firstStageId, null);
    }

    public ProductionBatch(UUID id, Instant recordedAt, UUID orderId, int batchNumber, int stoneCount,
            BigDecimal roughCarats, UUID firstStageId, String routeCode) {
        super(id, recordedAt);
        this.routeCode = routeCode;
        this.orderId = orderId;
        this.batchNumber = batchNumber;
        this.stoneCount = stoneCount;
        this.roughCarats = roughCarats;
        this.currentStones = new BigDecimal(stoneCount).setScale(4);
        this.currentCarats = roughCarats;
        this.currentStageId = firstStageId;
        this.status = BatchStatus.QUEUED;
        this.updatedAt = recordedAt;
    }

    /** Stores what the event history says about this batch. */
    public void project(BatchStatus status, UUID stageId, BigDecimal stones, BigDecimal carats, Instant recordedAt) {
        boolean changed = this.status != status
                || !Objects.equals(this.currentStageId, stageId)
                || this.currentStones.compareTo(stones) != 0
                || this.currentCarats.compareTo(carats) != 0;
        if (changed) {
            this.status = status;
            this.currentStageId = stageId;
            this.currentStones = stones;
            this.currentCarats = carats;
            this.updatedAt = recordedAt;
        }
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getBatchNumber() {
        return batchNumber;
    }

    public int getStoneCount() {
        return stoneCount;
    }

    public BigDecimal getRoughCarats() {
        return roughCarats;
    }

    public BigDecimal getCurrentStones() {
        return currentStones;
    }

    public BigDecimal getCurrentCarats() {
        return currentCarats;
    }

    public UUID getCurrentStageId() {
        return currentStageId;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public String getRouteCode() {
        return routeCode;
    }
}
