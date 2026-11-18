package com.realitycheck.simulation.backtest;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestRepository extends JpaRepository<Backtest, UUID> {

    Optional<Backtest> findFirstByFactoryIdOrderByCreatedAtDescIdDesc(UUID factoryId);
}
