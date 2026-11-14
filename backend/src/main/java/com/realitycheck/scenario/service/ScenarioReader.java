package com.realitycheck.scenario.service;

import com.realitycheck.scenario.domain.Scenario;
import com.realitycheck.scenario.persistence.ScenarioChangeRepository;
import com.realitycheck.scenario.persistence.ScenarioRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to scenarios for other modules, as plain records. */
@Service
@Transactional(readOnly = true)
public class ScenarioReader {

    /** @param baseline true when the scenario has no changes */
    public record ScenarioView(UUID id, UUID factoryId, String name, Instant forkAt, boolean includeFutureOrders,
            boolean baseline) {
    }

    private final ScenarioRepository scenarios;
    private final ScenarioChangeRepository changes;

    public ScenarioReader(ScenarioRepository scenarios, ScenarioChangeRepository changes) {
        this.scenarios = scenarios;
        this.changes = changes;
    }

    public Optional<ScenarioView> find(UUID id) {
        return scenarios.findById(id).map(s -> toView(s, counts(List.of(s.getId()))));
    }

    /** @return names of the scenarios that exist among the ids */
    public Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        scenarios.findAllById(ids).forEach(s -> names.put(s.getId(), s.getName()));
        return names;
    }

    /** Every scenario of the factory, oldest first. */
    public List<ScenarioView> forFactory(UUID factoryId) {
        List<Scenario> all = scenarios.findByFactoryIdOrderByCreatedAtAscIdAsc(factoryId);
        Map<UUID, Integer> counts = counts(all.stream().map(Scenario::getId).toList());
        return all.stream().map(s -> toView(s, counts)).toList();
    }

    private Map<UUID, Integer> counts(Collection<UUID> ids) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : changes.countByScenarioIds(ids)) {
                counts.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        return counts;
    }

    private static ScenarioView toView(Scenario s, Map<UUID, Integer> counts) {
        return new ScenarioView(s.getId(), s.getFactoryId(), s.getName(), s.getForkAt(), s.isIncludeFutureOrders(),
                counts.getOrDefault(s.getId(), 0) == 0);
    }
}
