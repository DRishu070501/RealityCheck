package com.realitycheck.scenario.service;

import com.realitycheck.factory.api.FactoryDtos.FactoryResponse;
import com.realitycheck.factory.service.FactoryCalendar;
import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.production.api.ProductionDtos.BatchState;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.ProductionReader;
import com.realitycheck.production.service.ProductionReader.OrderView;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.scenario.snapshot.SimulationEngineContract;
import com.realitycheck.scenario.snapshot.Snapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BatchSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.BreakSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CalendarSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.CapacityWindowSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.LimitsSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.OrderSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.PoolSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.RequirementSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.ShiftSnapshot;
import com.realitycheck.scenario.snapshot.Snapshot.StageSnapshot;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the base snapshot: the factory exactly as it stood at an instant, before any scenario
 * change. Configuration is read as it is now (calendar, active workflow, active pools with their
 * dated windows); work in progress is reconstructed from the event history at the fork (spec 05);
 * orders placed after the fork in recorded history can be included as future arrivals.
 *
 * <p>It only maps. It makes no decision about how the factory behaves; that is the engine's job.
 */
@Service
public class SnapshotAssembler {

    private final FactoryService factoryService;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final FactoryStateService factoryStateService;
    private final ProductionReader productionReader;
    private final SimulationEngineContract engine;
    private final Clock clock;

    public SnapshotAssembler(FactoryService factoryService, WorkflowReader workflowReader,
            ResourceReader resourceReader, FactoryStateService factoryStateService, ProductionReader productionReader,
            SimulationEngineContract engine, Clock clock) {
        this.factoryService = factoryService;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.factoryStateService = factoryStateService;
        this.productionReader = productionReader;
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * @param forkAt              truncated to whole seconds
     * @param includeFutureOrders include orders placed after the fork, at their recorded placement time
     */
    @Transactional(readOnly = true)
    public Snapshot build(UUID factoryId, Instant forkAt, boolean includeFutureOrders) {
        return build(factoryId, forkAt, includeFutureOrders, true);
    }

    /**
     * Configuration only (calendar, stages, pools and their windows), with no orders or batches.
     * Enough to check that changes apply and to describe them, without reconstructing history.
     */
    @Transactional(readOnly = true)
    public Snapshot configuration(UUID factoryId, Instant forkAt) {
        return build(factoryId, forkAt, false, false);
    }

    private Snapshot build(UUID factoryId, Instant forkAt, boolean includeFutureOrders, boolean withWork) {
        FactoryResponse factory = factoryService.get(factoryId);
        Instant fork = forkAt.truncatedTo(ChronoUnit.SECONDS);
        LimitsSnapshot limits = engine.defaultLimits();
        Instant horizon = fork.plusSeconds(limits.maxSimulatedSeconds());

        List<StageSnapshot> stages = workflowReader.activeStages(factoryId).stream()
                .map(SnapshotAssembler::toStage)
                .sorted(Comparator.comparingInt(StageSnapshot::sequence))
                .toList();

        // A job in progress at the fork may have started before it; windows from then on can matter.
        List<BatchState> stateBatches = withWork ? factoryStateService.stateAt(factoryId, fork).batches() : List.of();
        Instant earliest = stateBatches.stream()
                .filter(batch -> batch.status() != BatchStatus.COMPLETED)
                .map(BatchState::startedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(fork);
        Instant windowsFrom = earliest.minus(Duration.ofDays(1));
        List<PoolSnapshot> pools = resourceReader.forFactory(factoryId).stream()
                .filter(pool -> pool.status() == ResourceStatus.ACTIVE)
                .map(pool -> toPool(pool, windowsFrom, horizon))
                .sorted(Comparator.comparing(PoolSnapshot::id))
                .toList();

        Map<UUID, OrderSnapshot> orders = new TreeMap<>();
        List<BatchSnapshot> batches = new ArrayList<>();
        List<UUID> openOrderIds = stateBatches.stream()
                .filter(batch -> batch.status() != BatchStatus.COMPLETED)
                .map(BatchState::orderId)
                .distinct()
                .toList();
        Map<UUID, OrderView> open = openOrderIds.isEmpty() ? Map.of() : productionReader.byIds(openOrderIds);
        for (BatchState batch : stateBatches) {
            if (batch.status() == BatchStatus.COMPLETED) {
                continue;
            }
            orders.computeIfAbsent(batch.orderId(), id -> toOrder(open.get(id)));
            boolean inProgress = batch.status() == BatchStatus.IN_PROGRESS;
            batches.add(new BatchSnapshot(batch.batchId(), batch.orderId(), batch.batchNumber(),
                    inProgress ? BatchSnapshot.IN_PROGRESS : BatchSnapshot.QUEUED, batch.stageSequence(),
                    batch.inputStones(), batch.inputCarats(), inProgress ? null : batch.waitingSince(),
                    inProgress ? batch.startedAt() : null, null, null));
        }
        if (includeFutureOrders) {
            for (OrderView later : productionReader.placedBetween(factoryId, fork, clock.instant())) {
                orders.put(later.id(), toOrder(later));
                for (ProductionReader.BatchView batch : later.batches()) {
                    batches.add(new BatchSnapshot(batch.id(), later.id(), batch.batchNumber(),
                            BatchSnapshot.FUTURE_ARRIVAL, 1, BigDecimal.valueOf(batch.stoneCount()).setScale(4),
                            batch.roughCarats().setScale(4), null, null, later.placedAt(), null));
                }
            }
        }
        batches.sort(Comparator.comparing(BatchSnapshot::id));

        return new Snapshot(Snapshot.SCHEMA_VERSION, factoryId, factory.timeZone(), factory.currencyCode(), fork,
                toCalendar(factoryService.calendar(factoryId)), stages, pools, List.copyOf(orders.values()), batches,
                limits, engine.defaultBottleneck(), List.of(), List.of());
    }

    private static StageSnapshot toStage(StageView stage) {
        return new StageSnapshot(stage.id(), stage.code(), stage.name(), stage.sequence(), stage.stonesPerHour(),
                stage.setupSeconds(), stage.stoneYield(), stage.caratYield(), stage.fixedCostPerJob(),
                stage.requirements().stream()
                        .map(r -> new RequirementSnapshot(r.resourceId(), r.unitsPerSlot()))
                        .sorted(Comparator.comparing(RequirementSnapshot::poolId))
                        .toList(),
                stage.kind(), stage.delayDeciles());
    }

    private PoolSnapshot toPool(ResourceView pool, Instant from, Instant to) {
        List<CapacityWindowSnapshot> windows = resourceReader.windows(pool.id(), from, to).stream()
                .map(w -> new CapacityWindowSnapshot(w.startsAt(), w.endsAt(), w.capacityDelta()))
                .filter(w -> w.to().isAfter(w.from()))
                .sorted(Comparator.comparing(CapacityWindowSnapshot::from)
                        .thenComparing(CapacityWindowSnapshot::to)
                        .thenComparingInt(CapacityWindowSnapshot::delta))
                .toList();
        return new PoolSnapshot(pool.id(), pool.kind().name(), pool.category(), pool.name(), pool.capacity(),
                pool.hourlyCost(), windows);
    }

    private static OrderSnapshot toOrder(OrderView order) {
        return new OrderSnapshot(order.id(), order.orderNumber(), order.priority(), order.dueAt(), order.placedAt());
    }

    private static CalendarSnapshot toCalendar(FactoryCalendar calendar) {
        return new CalendarSnapshot(calendar.shifts().stream()
                .map(shift -> new ShiftSnapshot(shift.day().getValue(), shift.start(), shift.end(),
                        shift.breaks().stream().map(b -> new BreakSnapshot(b.start(), b.end())).toList()))
                .sorted(Comparator.comparingInt(ShiftSnapshot::dayOfWeek).thenComparing(ShiftSnapshot::startTime))
                .toList());
    }
}
