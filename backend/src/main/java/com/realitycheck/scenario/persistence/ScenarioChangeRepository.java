package com.realitycheck.scenario.persistence;

import com.realitycheck.scenario.domain.ScenarioChange;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ScenarioChangeRepository extends JpaRepository<ScenarioChange, UUID> {

    List<ScenarioChange> findByScenarioIdOrderByOrdinalAsc(UUID scenarioId);

    @Query("select c.scenarioId, count(c) from ScenarioChange c where c.scenarioId in :scenarioIds group by c.scenarioId")
    List<Object[]> countByScenarioIds(Collection<UUID> scenarioIds);
}
