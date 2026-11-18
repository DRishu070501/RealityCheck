package com.realitycheck.simulation.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.realitycheck.simulation.backtest.BacktestComparison.ForkComparison;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BacktestDtos {

    private BacktestDtos() {
    }

    /** @param forkAts past instants to simulate from; empty or absent for dates spread over the history */
    public record CreateBacktestRequest(@NotNull UUID factoryId, List<Instant> forkAts) {
    }

    /**
     * Across forks, the median of each error's absolute value: a typical error, not the best or worst.
     *
     * @param finishedError  median |simulated - recorded| / recorded of batches finished
     * @param leadTimeError  the same for median lead time
     * @param wipError       median of the per-fork work-in-progress error (sum of |differences| over recorded)
     */
    public record Overall(int forks,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal finishedError,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal leadTimeError,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal wipError) {
    }

    public record BacktestResponse(UUID id, UUID factoryId, String algorithmVersion, Instant createdAt,
            Overall overall, List<ForkComparison> forks) {
    }
}
