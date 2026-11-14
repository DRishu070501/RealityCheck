package com.realitycheck.production.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded fact about the factory. Immutable here and in the database: there are no setters,
 * every column is mapped non-updatable, and a trigger rejects any UPDATE or DELETE that arrives
 * another way. (Hibernate's {@code @Immutable} cannot be combined with the generated {@code seq}.)
 * {@code occurredAt} is when it happened; {@code createdAt} is when it was recorded.
 */
@Entity
@Table(name = "operational_event")
public class OperationalEvent extends BaseEntity {

    /** Recording order, assigned by the database. */
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    @Column(name = "stage_id", updatable = false)
    private UUID stageId;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private EventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "stone_quantity", precision = 14, scale = 4, updatable = false)
    private BigDecimal stoneQuantity;

    @Column(name = "carat_quantity", precision = 14, scale = 4, updatable = false)
    private BigDecimal caratQuantity;

    @Column(name = "duration_seconds", updatable = false)
    private Long durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Column(name = "corrects_event_id", updatable = false)
    private UUID correctsEventId;

    @Column(nullable = false, updatable = false)
    private boolean voids;

    protected OperationalEvent() {
    }

    private OperationalEvent(UUID id, Instant recordedAt) {
        super(id, recordedAt);
    }

    public static Builder builder(UUID id, Instant recordedAt, UUID factoryId, EventType type, Instant occurredAt) {
        OperationalEvent event = new OperationalEvent(id, recordedAt);
        event.factoryId = factoryId;
        event.eventType = type;
        event.occurredAt = occurredAt;
        event.payload = Map.of();
        return new Builder(event);
    }

    public static final class Builder {

        private final OperationalEvent event;

        private Builder(OperationalEvent event) {
            this.event = event;
        }

        public Builder order(UUID orderId) {
            event.orderId = orderId;
            return this;
        }

        public Builder batch(UUID batchId) {
            event.batchId = batchId;
            return this;
        }

        public Builder stage(UUID stageId) {
            event.stageId = stageId;
            return this;
        }

        public Builder resource(UUID resourceId) {
            event.resourceId = resourceId;
            return this;
        }

        public Builder quantities(BigDecimal stones, BigDecimal carats) {
            event.stoneQuantity = stones;
            event.caratQuantity = carats;
            return this;
        }

        public Builder duration(Long seconds) {
            event.durationSeconds = seconds;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            event.payload = payload == null ? Map.of() : Map.copyOf(payload);
            return this;
        }

        public Builder corrects(UUID targetEventId, boolean voids) {
            event.correctsEventId = targetEventId;
            event.voids = voids;
            return this;
        }

        public OperationalEvent build() {
            return event;
        }
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getStageId() {
        return stageId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public BigDecimal getStoneQuantity() {
        return stoneQuantity;
    }

    public BigDecimal getCaratQuantity() {
        return caratQuantity;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public UUID getCorrectsEventId() {
        return correctsEventId;
    }

    public boolean isVoids() {
        return voids;
    }
}
