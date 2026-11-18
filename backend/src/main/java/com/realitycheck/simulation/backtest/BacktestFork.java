package com.realitycheck.simulation.backtest;

import com.realitycheck.common.domain.BaseEntity;
import com.realitycheck.simulation.backtest.BacktestComparison.ForkComparison;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One fork date of a backtest and its comparison with recorded history. Never updated. */
@Entity
@Table(name = "backtest_fork")
public class BacktestFork extends BaseEntity {

    @Column(name = "backtest_id", nullable = false, updatable = false)
    private UUID backtestId;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "fork_at", nullable = false, updatable = false)
    private Instant forkAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private ForkComparison result;

    protected BacktestFork() {
    }

    public BacktestFork(UUID id, Instant now, UUID backtestId, int ordinal, ForkComparison result) {
        super(id, now);
        this.backtestId = backtestId;
        this.ordinal = ordinal;
        this.forkAt = result.forkAt();
        this.result = result;
    }

    public UUID getBacktestId() {
        return backtestId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public ForkComparison getResult() {
        return result;
    }
}
