package com.realitycheck.scenario.persistence;

import com.realitycheck.scenario.domain.Scenario;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    Page<Scenario> findByFactoryId(UUID factoryId, Pageable pageable);

    List<Scenario> findByFactoryIdOrderByCreatedAtAscIdAsc(UUID factoryId);

    boolean existsByFactoryIdAndNameIgnoreCase(UUID factoryId, String name);

    boolean existsByFactoryIdAndNameIgnoreCaseAndIdNot(UUID factoryId, String name, UUID id);
}
