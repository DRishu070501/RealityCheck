package com.realitycheck.scenario.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.production.service.ProductionReader;
import com.realitycheck.scenario.api.ScenarioDtos.CreateScenarioRequest;
import com.realitycheck.scenario.api.ScenarioDtos.LatestRun;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioChangeRequest;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioChangeResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioSummaryResponse;
import com.realitycheck.scenario.api.ScenarioDtos.ScenarioValidationResponse;
import com.realitycheck.scenario.api.ScenarioDtos.UpdateChangeRequest;
import com.realitycheck.scenario.api.ScenarioDtos.UpdateScenarioRequest;
import com.realitycheck.scenario.change.ChangeApplier;
import com.realitycheck.scenario.change.ChangeApplier.Change;
import com.realitycheck.scenario.change.ChangeApplier.ChangeApplicationException;
import com.realitycheck.scenario.change.ChangePayload;
import com.realitycheck.scenario.change.ChangePayloads;
import com.realitycheck.scenario.domain.Scenario;
import com.realitycheck.scenario.domain.ScenarioChange;
import com.realitycheck.scenario.persistence.ScenarioChangeRepository;
import com.realitycheck.scenario.persistence.ScenarioRepository;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.AppliedChange;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scenario and change management. Nothing here touches live configuration or recorded history:
 * changes are stored as validated payloads and applied to a copy only when a snapshot is built.
 *
 * <p>Every add, edit or delete of a change re-applies the scenario's whole change list to the
 * factory's configuration, so a change that would make a later one impossible (for example a
 * schedule replacement that an added shift now overlaps) is rejected at once with 400.
 */
@Service
public class ScenarioService {

    private final ScenarioRepository scenarios;
    private final ScenarioChangeRepository changes;
    private final FactoryResolver factoryResolver;
    private final ProductionReader productionReader;
    private final SnapshotAssembler assembler;
    private final ScenarioSnapshotService snapshotService;
    private final ScenarioRunLookup runLookup;
    private final Clock clock;

    public ScenarioService(ScenarioRepository scenarios, ScenarioChangeRepository changes,
            FactoryResolver factoryResolver, ProductionReader productionReader, SnapshotAssembler assembler,
            ScenarioSnapshotService snapshotService, ScenarioRunLookup runLookup, Clock clock) {
        this.scenarios = scenarios;
        this.changes = changes;
        this.factoryResolver = factoryResolver;
        this.productionReader = productionReader;
        this.assembler = assembler;
        this.snapshotService = snapshotService;
        this.runLookup = runLookup;
        this.clock = clock;
    }

    // ---- Scenarios ---------------------------------------------------------------------------

    @Transactional
    public ScenarioResponse create(CreateScenarioRequest request) {
        return create(request, Ids.random(), clock.instant());
    }

    /** Creation with a caller-chosen id and timestamp; the demonstration seed uses fixed values. */
    @Transactional
    public ScenarioResponse create(CreateScenarioRequest request, UUID id, Instant now) {
        UUID factoryId = factoryResolver.resolve(request.factoryId());
        Scenario parent = null;
        if (request.parentScenarioId() != null) {
            parent = scenarios.findById(request.parentScenarioId())
                    .filter(found -> found.getFactoryId().equals(factoryId))
                    .orElseThrow(() -> new ValidationException("parentScenarioId",
                            "is not a scenario of this factory"));
        }
        Instant forkAt = request.forkAt() != null ? request.forkAt() : parent != null ? parent.getForkAt() : null;
        if (forkAt == null) {
            throw new ValidationException("forkAt", "is required unless parentScenarioId is given");
        }
        forkAt = forkAt.truncatedTo(ChronoUnit.SECONDS);
        checkForkAt(factoryId, forkAt, now);
        String name = request.name().strip();
        if (scenarios.existsByFactoryIdAndNameIgnoreCase(factoryId, name)) {
            throw ApiException.conflict("A scenario named \"" + name + "\" already exists in this factory");
        }
        boolean includeFuture = request.includeFutureOrders() != null ? request.includeFutureOrders()
                : parent == null || parent.isIncludeFutureOrders();

        Scenario scenario = scenarios.saveAndFlush(new Scenario(id, now, factoryId, name,
                blankToNull(request.description()), parent == null ? null : parent.getId(), forkAt, includeFuture));
        if (parent != null) {
            // Copied once, now: later edits to the parent never reach the child.
            for (ScenarioChange inherited : changes.findByScenarioIdOrderByOrdinalAsc(parent.getId())) {
                changes.save(new ScenarioChange(Ids.child(id, "change:" + inherited.getOrdinal()), now, id,
                        inherited.getOrdinal(), inherited.getChangeType(), inherited.getPayload(), true));
            }
            changes.flush();
            List<ScenarioChange> copied = changes.findByScenarioIdOrderByOrdinalAsc(id);
            checkApplicable(scenario, toChanges(copied), -1);
        }
        return toResponse(scenario);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ScenarioSummaryResponse> list(UUID factoryIdOrNull, Pageable pageable) {
        UUID factoryId = factoryResolver.resolve(factoryIdOrNull);
        Page<Scenario> page = scenarios.findByFactoryId(factoryId, pageable);
        List<UUID> ids = page.getContent().stream().map(Scenario::getId).toList();
        Map<UUID, Integer> counts = changeCounts(ids);
        Map<UUID, ScenarioRunLookup.LatestRun> latest = ids.isEmpty() ? Map.of() : runLookup.latestRuns(ids);
        return PageResponse.from(page, s -> {
            int count = counts.getOrDefault(s.getId(), 0);
            return new ScenarioSummaryResponse(s.getId(), s.getFactoryId(), s.getName(), s.getDescription(),
                    s.getParentScenarioId(), s.getForkAt(), s.isIncludeFutureOrders(), count == 0, count,
                    toLatest(latest.get(s.getId())), s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
        });
    }

    @Transactional
    public ScenarioResponse update(UUID id, UpdateScenarioRequest request) {
        Scenario scenario = find(id);
        if (scenario.getVersion() != request.version()) {
            throw new OptimisticLockingFailureException("Stale version for scenario " + id);
        }
        Instant now = clock.instant();
        String name = request.name() == null ? scenario.getName() : request.name().strip();
        if (name.isEmpty()) {
            throw new ValidationException("name", "must not be blank");
        }
        if (!name.equalsIgnoreCase(scenario.getName())
                && scenarios.existsByFactoryIdAndNameIgnoreCaseAndIdNot(scenario.getFactoryId(), name, id)) {
            throw ApiException.conflict("A scenario named \"" + name + "\" already exists in this factory");
        }
        String description = request.description() == null ? scenario.getDescription()
                : blankToNull(request.description());
        Instant forkAt = request.forkAt() == null ? scenario.getForkAt()
                : request.forkAt().truncatedTo(ChronoUnit.SECONDS);
        boolean forkChanges = !forkAt.equals(scenario.getForkAt());
        boolean includeFuture = request.includeFutureOrders() == null ? scenario.isIncludeFutureOrders()
                : request.includeFutureOrders();
        if ((forkChanges || includeFuture != scenario.isIncludeFutureOrders()) && runLookup.hasRuns(id)) {
            throw ApiException.conflict("This scenario has simulation runs, so its starting point cannot change."
                    + " Create a new scenario (it can branch from this one) instead.");
        }
        if (forkChanges) {
            checkForkAt(scenario.getFactoryId(), forkAt, now);
        }
        scenario.update(name, description, forkAt, includeFuture, now);
        Scenario saved = scenarios.saveAndFlush(scenario);
        if (forkChanges) {
            checkApplicable(saved, toChanges(changes.findByScenarioIdOrderByOrdinalAsc(id)), -1);
        }
        return toResponse(saved);
    }

    // ---- Changes -----------------------------------------------------------------------------

    @Transactional
    public ScenarioChangeResponse addChange(UUID scenarioId, ScenarioChangeRequest request) {
        return addChange(scenarioId, request, Ids.random(), clock.instant());
    }

    @Transactional
    public ScenarioChangeResponse addChange(UUID scenarioId, ScenarioChangeRequest request, UUID id, Instant now) {
        Scenario scenario = find(scenarioId);
        ChangePayload payload = ChangePayloads.parseValid(request.changeType(), request.payload(),
                scenario.getForkAt());
        List<ScenarioChange> existing = changes.findByScenarioIdOrderByOrdinalAsc(scenarioId);
        int ordinal = existing.size() + 1;
        List<Change> all = new ArrayList<>(toChanges(existing));
        all.add(new Change(ordinal, payload));
        checkApplicable(scenario, all, ordinal);

        ScenarioChange change = changes.saveAndFlush(new ScenarioChange(id, now, scenarioId, ordinal,
                request.changeType(), ChangePayloads.toJson(payload).toString(), false));
        bump(scenario, now);
        return describe(scenario, change);
    }

    @Transactional
    public ScenarioChangeResponse replaceChange(UUID scenarioId, UUID changeId, UpdateChangeRequest request) {
        Scenario scenario = find(scenarioId);
        ScenarioChange change = findChange(scenarioId, changeId);
        if (request.changeType() != null && request.changeType() != change.getChangeType()) {
            throw new ValidationException("changeType", "cannot change; delete this change and add a new one");
        }
        ChangePayload payload = ChangePayloads.parseValid(change.getChangeType(), request.payload(),
                scenario.getForkAt());
        List<Change> all = changes.findByScenarioIdOrderByOrdinalAsc(scenarioId).stream()
                .map(c -> c.getId().equals(changeId) ? new Change(c.getOrdinal(), payload) : toChange(c))
                .toList();
        checkApplicable(scenario, all, change.getOrdinal());
        Instant now = clock.instant();
        change.replacePayload(ChangePayloads.toJson(payload).toString(), now);
        changes.saveAndFlush(change);
        bump(scenario, now);
        return describe(scenario, change);
    }

    @Transactional
    public void deleteChange(UUID scenarioId, UUID changeId) {
        Scenario scenario = find(scenarioId);
        ScenarioChange change = findChange(scenarioId, changeId);
        List<ScenarioChange> remaining = changes.findByScenarioIdOrderByOrdinalAsc(scenarioId).stream()
                .filter(c -> !c.getId().equals(changeId))
                .toList();
        List<Change> renumbered = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i++) {
            renumbered.add(new Change(i + 1, toChange(remaining.get(i)).payload()));
        }
        checkApplicable(scenario, renumbered, -1);
        Instant now = clock.instant();
        changes.delete(change);
        changes.flush();
        // Ordinals stay 1..n without gaps; the unique constraint is checked at commit.
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).getOrdinal() != i + 1) {
                remaining.get(i).renumber(i + 1, now);
            }
        }
        changes.saveAllAndFlush(remaining);
        bump(scenario, now);
    }

    // ---- Validation --------------------------------------------------------------------------

    /** Assembles the snapshot, applies the changes and checks them, persisting nothing. */
    @Transactional(readOnly = true)
    public ScenarioValidationResponse validate(UUID scenarioId) {
        ScenarioSnapshotService.Assembled assembled = snapshotService.assemble(scenarioId);
        return new ScenarioValidationResponse(assembled.valid(), assembled.errors(), assembled.warnings(),
                assembled.snapshot() == null ? null
                        : ScenarioSnapshotService.preview(assembled.base(), assembled.snapshot()),
                assembled.baseStateHash(), assembled.inputHash());
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private void checkForkAt(UUID factoryId, Instant forkAt, Instant now) {
        if (forkAt.isAfter(now)) {
            throw new ValidationException("forkAt", "must not be in the future: there is no recorded state to start from");
        }
        Instant first = productionReader.firstEventAt(factoryId);
        if (first != null && forkAt.isBefore(first)) {
            throw new ValidationException("forkAt", "must not be before the factory's first recorded event ("
                    + first + ")");
        }
    }

    /**
     * Applies the whole list to the factory's configuration at the fork.
     *
     * @param ordinalOfRequest the change being added or edited, whose errors name its payload
     *                         fields directly; other changes are named by ordinal
     */
    private void checkApplicable(Scenario scenario, List<Change> list, int ordinalOfRequest) {
        if (list.isEmpty()) {
            return;
        }
        Snapshot configuration = assembler.configuration(scenario.getFactoryId(), scenario.getForkAt());
        for (Change change : list) {
            var errors = ChangePayloads.validate(change.payload(), scenario.getForkAt());
            if (!errors.isEmpty()) {
                throw new ValidationException(change.ordinal() == ordinalOfRequest ? errors
                        : errors.stream().map(e -> new com.realitycheck.common.error.FieldViolation(
                                "changes[" + change.ordinal() + "]." + e.field(), e.message())).toList());
            }
        }
        try {
            ChangeApplier.apply(configuration, list);
        } catch (ChangeApplicationException ex) {
            String field = ex.ordinal() == ordinalOfRequest ? ex.field() : "changes[" + ex.ordinal() + "]." + ex.field();
            throw new ValidationException(field, ex.getMessage());
        }
    }

    private void bump(Scenario scenario, Instant now) {
        scenario.touch(now);
        scenarios.saveAndFlush(scenario);
    }

    private ScenarioResponse toResponse(Scenario scenario) {
        List<ScenarioChange> stored = changes.findByScenarioIdOrderByOrdinalAsc(scenario.getId());
        Map<Integer, String> summaries = summaries(scenario, stored);
        List<ScenarioChangeResponse> changeResponses = stored.stream()
                .map(c -> new ScenarioChangeResponse(c.getId(), c.getOrdinal(), c.getChangeType(), c.isInherited(),
                        ScenarioSnapshotService.readTree(c.getPayload()), summaries.get(c.getOrdinal())))
                .toList();
        ScenarioRunLookup.LatestRun latest = runLookup.latestRuns(List.of(scenario.getId())).get(scenario.getId());
        return new ScenarioResponse(scenario.getId(), scenario.getFactoryId(), scenario.getName(),
                scenario.getDescription(), scenario.getParentScenarioId(), scenario.getForkAt(),
                scenario.isIncludeFutureOrders(), stored.isEmpty(), changeResponses, toLatest(latest),
                scenario.getVersion(), scenario.getCreatedAt(), scenario.getUpdatedAt());
    }

    private ScenarioChangeResponse describe(Scenario scenario, ScenarioChange change) {
        List<ScenarioChange> stored = changes.findByScenarioIdOrderByOrdinalAsc(scenario.getId());
        return new ScenarioChangeResponse(change.getId(), change.getOrdinal(), change.getChangeType(),
                change.isInherited(), ScenarioSnapshotService.readTree(change.getPayload()),
                summaries(scenario, stored).get(change.getOrdinal()));
    }

    /** Summaries by ordinal, from the changes applied in order to the current configuration. */
    private Map<Integer, String> summaries(Scenario scenario, List<ScenarioChange> stored) {
        Map<Integer, String> result = new HashMap<>();
        if (stored.isEmpty()) {
            return result;
        }
        try {
            Snapshot configuration = assembler.configuration(scenario.getFactoryId(), scenario.getForkAt());
            for (AppliedChange applied : ChangeApplier.apply(configuration, toChanges(stored)).snapshot()
                    .appliedChanges()) {
                result.put(applied.ordinal(), applied.summary());
            }
        } catch (ChangeApplicationException | ApiException ex) {
            // The configuration changed since the change was saved; validate reports why.
            for (ScenarioChange change : stored) {
                result.putIfAbsent(change.getOrdinal(), change.getChangeType() + " (cannot be applied to the"
                        + " current configuration; validate the scenario for details)");
            }
        }
        return result;
    }

    private Map<UUID, Integer> changeCounts(Collection<UUID> ids) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        for (Object[] row : changes.countByScenarioIds(ids)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private static List<Change> toChanges(List<ScenarioChange> stored) {
        return stored.stream().map(ScenarioService::toChange).toList();
    }

    private static Change toChange(ScenarioChange change) {
        JsonNode json = ScenarioSnapshotService.readTree(change.getPayload());
        return new Change(change.getOrdinal(), ChangePayloads.parse(change.getChangeType(), json));
    }

    private static LatestRun toLatest(ScenarioRunLookup.LatestRun run) {
        return run == null ? null : new LatestRun(run.runId(), run.status(), run.finishedAt());
    }

    private Scenario find(UUID id) {
        return scenarios.findById(id).orElseThrow(() -> ApiException.notFound("Scenario", id));
    }

    private ScenarioChange findChange(UUID scenarioId, UUID changeId) {
        return changes.findById(changeId)
                .filter(change -> change.getScenarioId().equals(scenarioId))
                .orElseThrow(() -> ApiException.notFound("Scenario change", changeId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
