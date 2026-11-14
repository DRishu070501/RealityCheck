package com.realitycheck.scenario.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.scenario.api.ScenarioDtos.PoolPreview;
import com.realitycheck.scenario.api.ScenarioDtos.Preview;
import com.realitycheck.scenario.api.ScenarioDtos.StagePreview;
import com.realitycheck.scenario.api.ScenarioDtos.ValidationProblem;
import com.realitycheck.scenario.api.ScenarioDtos.ValidationWarning;
import com.realitycheck.scenario.change.ChangeApplier;
import com.realitycheck.scenario.change.ChangeApplier.Change;
import com.realitycheck.scenario.change.ChangeApplier.ChangeApplicationException;
import com.realitycheck.scenario.change.ChangePayloads;
import com.realitycheck.scenario.domain.Scenario;
import com.realitycheck.scenario.domain.ScenarioChange;
import com.realitycheck.scenario.persistence.ScenarioChangeRepository;
import com.realitycheck.scenario.persistence.ScenarioRepository;
import com.realitycheck.scenario.snapshot.SimulationEngineContract;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.ShiftSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import com.realitycheck.scenario.snapshot.SnapshotJson;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a scenario into the immutable input of a simulation run: base snapshot at the fork, the
 * scenario's changes applied in ordinal order, the engine's own validation, and both hashes. The
 * single entry point used by run submission and by the validate endpoint. It persists nothing.
 */
@Service
public class ScenarioSnapshotService {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ScenarioRepository scenarios;
    private final ScenarioChangeRepository changes;
    private final SnapshotAssembler assembler;
    private final SimulationEngineContract engine;

    public ScenarioSnapshotService(ScenarioRepository scenarios, ScenarioChangeRepository changes,
            SnapshotAssembler assembler, SimulationEngineContract engine) {
        this.scenarios = scenarios;
        this.changes = changes;
        this.assembler = assembler;
        this.engine = engine;
    }

    /**
     * @param base          the factory at the fork with no changes
     * @param snapshot      with the changes applied; null when a change could not be applied
     * @param baseStateHash hash of {@code base}: equal for two scenarios with the same fork and
     *                      no history or configuration change in between
     * @param inputHash     hash of {@code snapshot} with the algorithm version; null with {@code snapshot}
     * @param errors        empty when the scenario can be simulated
     */
    public record Assembled(UUID scenarioId, UUID factoryId, String scenarioName, Snapshot base, Snapshot snapshot,
            String baseStateHash, String inputHash, List<ValidationProblem> errors,
            List<ValidationWarning> warnings) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Assembled assemble(UUID scenarioId) {
        Scenario scenario = scenarios.findById(scenarioId)
                .orElseThrow(() -> ApiException.notFound("Scenario", scenarioId));
        List<ScenarioChange> stored = changes.findByScenarioIdOrderByOrdinalAsc(scenarioId);
        Map<Integer, UUID> changeIds = stored.stream()
                .collect(Collectors.toMap(ScenarioChange::getOrdinal, ScenarioChange::getId));

        Snapshot base = assembler.build(scenario.getFactoryId(), scenario.getForkAt(),
                scenario.isIncludeFutureOrders());
        String baseStateHash = SnapshotJson.hash(base);

        List<ValidationProblem> errors = new ArrayList<>();
        ChangeApplier.Result applied;
        try {
            applied = ChangeApplier.apply(base, toChanges(stored));
        } catch (ChangeApplicationException ex) {
            // The configuration changed after the change was saved, for example a pool was deactivated.
            errors.add(new ValidationProblem(changeIds.get(ex.ordinal()), "CHANGE_NOT_APPLICABLE",
                    "Change " + ex.ordinal() + ": " + ex.field() + " " + ex.getMessage(), null));
            return new Assembled(scenarioId, scenario.getFactoryId(), scenario.getName(), base, null, baseStateHash,
                    null, errors, List.of());
        } catch (ApiException ex) {
            errors.add(new ValidationProblem(null, "CHANGE_NOT_APPLICABLE", ex.getMessage(), null));
            return new Assembled(scenarioId, scenario.getFactoryId(), scenario.getName(), base, null, baseStateHash,
                    null, errors, List.of());
        }
        Snapshot snapshot = applied.snapshot();
        for (SimulationEngineContract.EngineProblem problem : engine.check(snapshot)) {
            errors.add(new ValidationProblem(null, problem.code(), problem.message(), problem.subject()));
        }
        List<ValidationWarning> warnings = applied.warnings().stream()
                .map(w -> new ValidationWarning(changeIds.get(w.ordinal()), w.ordinal(), w.code(), w.message()))
                .toList();
        String inputHash = inputHash(snapshot, engine.algorithmVersion());
        return new Assembled(scenarioId, scenario.getFactoryId(), scenario.getName(), base, snapshot, baseStateHash,
                inputHash, errors, warnings);
    }

    /** The hash that identifies a run's input: the snapshot with its changes, and the algorithm. */
    public static String inputHash(Snapshot snapshot, String algorithmVersion) {
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("algorithmVersion", algorithmVersion);
        hashed.put("snapshot", snapshot);
        return SnapshotJson.hash(hashed);
    }

    static List<Change> toChanges(List<ScenarioChange> stored) {
        return stored.stream()
                .map(change -> new Change(change.getOrdinal(),
                        ChangePayloads.parse(change.getChangeType(), readTree(change.getPayload()))))
                .toList();
    }

    static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Stored change payload is not JSON", ex);
        }
    }

    /** Configuration before and after the changes, for the validate endpoint. */
    public static Preview preview(Snapshot before, Snapshot after) {
        Map<UUID, PoolSnapshot> afterPools = byId(after.pools(), PoolSnapshot::id);
        List<PoolPreview> pools = before.pools().stream()
                .map(pool -> {
                    PoolSnapshot changed = afterPools.get(pool.id());
                    return new PoolPreview(pool.id(), pool.name(), pool.kind(), pool.baseCapacity(),
                            changed.baseCapacity(), changed.windows().size() - pool.windows().size());
                })
                .toList();
        Map<UUID, StageSnapshot> afterStages = byId(after.stages(), StageSnapshot::id);
        List<StagePreview> stages = before.stages().stream()
                .map(stage -> {
                    StageSnapshot changed = afterStages.get(stage.id());
                    return new StagePreview(stage.id(), stage.code(), stage.name(), stage.stonesPerHour(),
                            changed.stonesPerHour(), maxSlots(stage, before), maxSlots(changed, after));
                })
                .toList();
        return new Preview(pools, stages, weeklySeconds(before), weeklySeconds(after), unstartedStones(before),
                unstartedStones(after));
    }

    private static Integer maxSlots(StageSnapshot stage, Snapshot snapshot) {
        Map<UUID, PoolSnapshot> pools = byId(snapshot.pools(), PoolSnapshot::id);
        return stage.requirements().stream()
                .map(r -> {
                    PoolSnapshot pool = pools.get(r.poolId());
                    return pool == null ? 0 : pool.baseCapacity() / r.unitsPerSlot();
                })
                .min(Integer::compare)
                .orElse(null);
    }

    private static long weeklySeconds(Snapshot snapshot) {
        long total = 0;
        for (ShiftSnapshot shift : snapshot.calendar().shifts()) {
            total += Duration.between(shift.startTime(), shift.endTime()).toSeconds();
            for (Snapshot.BreakSnapshot pause : shift.breaks()) {
                total -= Duration.between(pause.startTime(), pause.endTime()).toSeconds();
            }
        }
        return total;
    }

    private static BigDecimal unstartedStones(Snapshot snapshot) {
        return snapshot.batches().stream()
                .filter(ChangeApplier::unstarted)
                .map(BatchSnapshot::stones)
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }

    private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, item -> item));
    }
}
