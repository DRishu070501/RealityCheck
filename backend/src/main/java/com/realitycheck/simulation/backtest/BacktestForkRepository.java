package com.realitycheck.simulation.backtest;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestForkRepository extends JpaRepository<BacktestFork, UUID> {

    List<BacktestFork> findByBacktestIdOrderByOrdinalAsc(UUID backtestId);
}
