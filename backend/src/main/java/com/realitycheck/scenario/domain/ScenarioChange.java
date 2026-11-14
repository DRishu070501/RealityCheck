package com.realitycheck.scenario.domain;

import com.realitycheck.common.domain.BaseEntity;
import com.realitycheck.scenario.change.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One change of a scenario. {@code payload} is the JSON of the matching
 * {@link com.realitycheck.scenario.change.ChangePayload} record, validated before it is stored.
 */
@Entity
@Table(name = "scenario_change")
public class ScenarioChange extends BaseEntity {

    @Column(name = "scenario_id", nullable = false, updatable = false)
    private UUID scenarioId;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 32)
    private ChangeType changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false, updatable = false)
    private boolean inherited;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScenarioChange() {
    }

    public ScenarioChange(UUID id, Instant now, UUID scenarioId, int ordinal, ChangeType changeType, String payload,
            boolean inherited) {
        super(id, now);
        this.scenarioId = scenarioId;
        this.ordinal = ordinal;
        this.changeType = changeType;
        this.payload = payload;
        this.inherited = inherited;
        this.updatedAt = now;
    }

    public void replacePayload(String newPayload, Instant now) {
        this.payload = newPayload;
        this.updatedAt = now;
    }

    public void renumber(int newOrdinal, Instant now) {
        this.ordinal = newOrdinal;
        this.updatedAt = now;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isInherited() {
        return inherited;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
