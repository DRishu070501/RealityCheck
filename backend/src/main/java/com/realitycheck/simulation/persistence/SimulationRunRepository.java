package com.realitycheck.simulation.persistence;

import com.realitycheck.simulation.domain.RunStatus;
import com.realitycheck.simulation.domain.SimulationRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, UUID> {

    Optional<SimulationRun> findByScenarioIdAndIdempotencyKey(UUID scenarioId, String idempotencyKey);

    Optional<SimulationRun> findFirstByScenarioIdAndInputHashAndStatusOrderBySubmittedAtDesc(UUID scenarioId,
            String inputHash, RunStatus status);

    Optional<SimulationRun> findFirstByScenarioIdAndStatusOrderBySubmittedAtDescIdDesc(UUID scenarioId,
            RunStatus status);

    Optional<SimulationRun> findFirstByScenarioIdAndStatusAndBaseStateHashOrderBySubmittedAtDescIdDesc(
            UUID scenarioId, RunStatus status, String baseStateHash);

    Page<SimulationRun> findByScenarioId(UUID scenarioId, Pageable pageable);

    boolean existsByScenarioId(UUID scenarioId);

    /** The newest run of each scenario. */
    @Query("""
            select r from SimulationRun r
            where r.scenarioId in :scenarioIds
              and r.submittedAt = (select max(o.submittedAt) from SimulationRun o where o.scenarioId = r.scenarioId)
            order by r.id""")
    List<SimulationRun> findLatestByScenarioIds(Collection<UUID> scenarioIds);
}
