package com.realitycheck.simulation.backtest;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A request to check the engine against recorded history from one or more past fork dates. Never updated. */
@Entity
@Table(name = "backtest")
public class Backtest extends BaseEntity {

    @Column(name = "factory_id", nullable = false, updatable = false)
    private UUID factoryId;

    @Column(name = "algorithm_version", nullable = false, updatable = false, length = 20)
    private String algorithmVersion;

    protected Backtest() {
    }

    public Backtest(UUID id, Instant now, UUID factoryId, String algorithmVersion) {
        super(id, now);
        this.factoryId = factoryId;
        this.algorithmVersion = algorithmVersion;
    }

    public UUID getFactoryId() {
        return factoryId;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }
}
