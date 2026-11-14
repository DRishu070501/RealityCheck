package com.realitycheck.simulation.service;

import com.realitycheck.factory.service.FactoryCalendar;
import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.production.api.ProductionDtos.BatchState;
import com.realitycheck.production.api.ProductionDtos.FactoryStateResponse;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.service.FactoryStateService;
import com.realitycheck.production.service.ProductionReader;
import com.realitycheck.production.service.ProductionReader.OrderView;
import com.realitycheck.resource.domain.ResourceStatus;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.simulation.engine.SimulationInput;
import com.realitycheck.simulation.engine.SimulationInput.BatchEntryState;
import com.realitycheck.simulation.engine.SimulationInput.BatchSpec;
import com.realitycheck.simulation.engine.SimulationInput.BottleneckConfig;
import com.realitycheck.simulation.engine.SimulationInput.BreakSpec;
import com.realitycheck.simulation.engine.SimulationInput.CalendarSpec;
import com.realitycheck.simulation.engine.SimulationInput.CapacityWindow;
import com.realitycheck.simulation.engine.SimulationInput.OrderSpec;
import com.realitycheck.simulation.engine.SimulationInput.PoolSpec;
import com.realitycheck.simulation.engine.SimulationInput.RequirementSpec;
import com.realitycheck.simulation.engine.SimulationInput.ResourceKind;
import com.realitycheck.simulation.engine.SimulationInput.ShiftSpec;
import com.realitycheck.simulation.engine.SimulationInput.SimulationLimits;
import com.realitycheck.simulation.engine.SimulationInput.StageSpec;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the engine's input for a factory as it stood at an instant: the calendar, the active
 * workflow, the resource pools with their dated windows, the work in progress reconstructed from
 * the event history, and the orders placed after that instant.
 *
 * <p>This is the only place application data is turned into engine input, and it only maps:
 * instants become epoch seconds, local times become seconds of day. It makes no decisions about
 * how the factory behaves; those belong to the engine.
 */
@Service
public class SimulationInputAssembler {

    private final FactoryService factoryService;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final FactoryStateService factoryStateService;
    private final ProductionReader productionReader;
    private final Clock clock;

    public SimulationInputAssembler(FactoryService factoryService, WorkflowReader workflowReader,
            ResourceReader resourceReader, FactoryStateService factoryStateService,
            ProductionReader productionReader, Clock clock) {
        this.factoryService = factoryService;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.factoryStateService = factoryStateService;
        this.productionReader = productionReader;
        this.clock = clock;
    }

    /**
     * The factory exactly as recorded, with nothing changed: the baseline every scenario is
     * compared against.
     *
     * @param forkAt the instant to simulate from, truncated to whole seconds
     */
    @Transactional(readOnly = true)
    public SimulationInput baseline(UUID factoryId, Instant forkAt) {
        Instant fork = forkAt.truncatedTo(ChronoUnit.SECONDS);
        long forkTime = fork.getEpochSecond();
        SimulationLimits limits = SimulationLimits.defaults();
        Instant horizon = fork.plusSeconds(limits.maxSimulatedSeconds());

        List<StageSpec> stages = workflowReader.activeStages(factoryId).stream().map(this::toStage).toList();

        // A job in progress at the fork may have started before it; windows from then on can matter.
        FactoryStateResponse state = factoryStateService.stateAt(factoryId, fork);
        Instant earliest = state.batches().stream()
                .map(BatchState::startedAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(fork);
        List<PoolSpec> pools = resourceReader.forFactory(factoryId).stream()
                .filter(pool -> pool.status() == ResourceStatus.ACTIVE)
                .map(pool -> toPool(pool, earliest.minus(Duration.ofDays(1)), horizon))
                .toList();

        Map<UUID, OrderSpec> orders = new TreeMap<>();
        List<BatchSpec> batches = new ArrayList<>();
        Map<UUID, OrderView> ordersInProgress = productionReader.byIds(state.batches().stream()
                .filter(batch -> batch.status() != BatchStatus.COMPLETED)
                .map(BatchState::orderId)
                .distinct()
                .toList());
        for (BatchState batch : state.batches()) {
            if (batch.status() == BatchStatus.COMPLETED) {
                continue;
            }
            orders.computeIfAbsent(batch.orderId(), id -> toOrder(ordersInProgress.get(id)));
            boolean inProgress = batch.status() == BatchStatus.IN_PROGRESS;
            batches.add(new BatchSpec(batch.batchId(), batch.orderId(),
                    inProgress ? BatchEntryState.IN_PROGRESS : BatchEntryState.QUEUED,
                    batch.stageSequence(), batch.inputStones(), batch.inputCarats(),
                    inProgress ? null : epochSecond(batch.waitingSince()),
                    inProgress ? epochSecond(batch.startedAt()) : null, null));
        }
        // Orders the history already knows were placed later arrive at the first stage when placed.
        for (OrderView later : productionReader.placedBetween(factoryId, fork, clock.instant())) {
            orders.put(later.id(), toOrder(later));
            for (ProductionReader.BatchView batch : later.batches()) {
                batches.add(new BatchSpec(batch.id(), later.id(), BatchEntryState.FUTURE_ARRIVAL, 1,
                        BigDecimal.valueOf(batch.stoneCount()), batch.roughCarats(), null, null,
                        later.placedAt().getEpochSecond()));
            }
        }

        return new SimulationInput(forkTime, toCalendar(factoryService.calendar(factoryId)), stages, pools,
                List.copyOf(orders.values()), batches, limits, BottleneckConfig.defaults());
    }

    private StageSpec toStage(StageView stage) {
        return new StageSpec(stage.id(), stage.code(), stage.sequence(), stage.stonesPerHour(),
                stage.setupSeconds(), stage.stoneYield(), stage.caratYield(), stage.fixedCostPerJob(),
                stage.requirements().stream()
                        .map(requirement -> new RequirementSpec(requirement.resourceId(),
                                requirement.unitsPerSlot()))
                        .toList());
    }

    private PoolSpec toPool(ResourceView pool, Instant from, Instant to) {
        List<CapacityWindow> windows = resourceReader.windows(pool.id(), from, to).stream()
                .map(window -> new CapacityWindow(window.startsAt().getEpochSecond(),
                        window.endsAt().getEpochSecond(), window.capacityDelta()))
                .filter(window -> window.to() > window.from())
                .toList();
        return new PoolSpec(pool.id(), ResourceKind.valueOf(pool.kind().name()), pool.name(), pool.capacity(),
                pool.hourlyCost(), windows);
    }

    private static OrderSpec toOrder(OrderView order) {
        return new OrderSpec(order.id(), order.orderNumber(), order.priority(),
                order.dueAt() == null ? null : order.dueAt().getEpochSecond());
    }

    private static CalendarSpec toCalendar(FactoryCalendar calendar) {
        return new CalendarSpec(calendar.zone().getId(), calendar.shifts().stream()
                .map(shift -> new ShiftSpec(shift.day().getValue(), shift.start().toSecondOfDay(),
                        shift.end().toSecondOfDay(),
                        shift.breaks().stream()
                                .map(pause -> new BreakSpec(pause.start().toSecondOfDay(),
                                        pause.end().toSecondOfDay()))
                                .toList()))
                .toList());
    }

    private static Long epochSecond(Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }
}
