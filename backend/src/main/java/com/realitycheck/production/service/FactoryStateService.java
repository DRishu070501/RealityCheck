package com.realitycheck.production.service;

import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.factory.service.FactoryService;
import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.BatchState;
import com.realitycheck.production.api.ProductionDtos.DailyOutput;
import com.realitycheck.production.api.ProductionDtos.FactoryOverviewResponse;
import com.realitycheck.production.api.ProductionDtos.FactoryStateResponse;
import com.realitycheck.production.api.ProductionDtos.OrderCounts;
import com.realitycheck.production.api.ProductionDtos.ResourceState;
import com.realitycheck.production.api.ProductionDtos.StageState;
import com.realitycheck.production.api.ProductionDtos.UnitsInUse;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.domain.OrderStatus;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.domain.ProductionOrder;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.production.persistence.ProductionOrderRepository;
import com.realitycheck.production.service.BatchHistory.Projection;
import com.realitycheck.production.service.EventQueryService.EventFilter;
import com.realitycheck.resource.domain.ResourceKind;
import com.realitycheck.resource.service.CapacityTimeline;
import com.realitycheck.resource.service.ResourceReader;
import com.realitycheck.resource.service.ResourceReader.ResourceView;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.RequirementView;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds the factory as it stood at an instant by replaying the event history, and composes
 * the dashboard overview from the state now. Read-only: reconstruction never writes anything.
 */
@Service
@Transactional(readOnly = true)
public class FactoryStateService {

    private static final int OVERVIEW_DAYS = 14;
    private static final int RECENT_EVENTS = 10;

    private final ProductionOrderRepository orders;
    private final ProductionBatchRepository batches;
    private final OperationalEventRepository events;
    private final WorkflowReader workflowReader;
    private final ResourceReader resourceReader;
    private final FactoryResolver factoryResolver;
    private final FactoryService factoryService;
    private final EventQueryService eventQueryService;
    private final Clock clock;

    public FactoryStateService(ProductionOrderRepository orders, ProductionBatchRepository batches,
            OperationalEventRepository events, WorkflowReader workflowReader, ResourceReader resourceReader,
            FactoryResolver factoryResolver, FactoryService factoryService, EventQueryService eventQueryService,
            Clock clock) {
        this.orders = orders;
        this.batches = batches;
        this.events = events;
        this.workflowReader = workflowReader;
        this.resourceReader = resourceReader;
        this.factoryResolver = factoryResolver;
        this.factoryService = factoryService;
        this.eventQueryService = eventQueryService;
        this.clock = clock;
    }

    /**
     * @param atOrNull the instant to reconstruct; now when null. Before the first event the
     *                 state is simply empty.
     */
    public FactoryStateResponse stateAt(UUID factoryId, Instant atOrNull) {
        factoryResolver.require(factoryId);
        Instant at = atOrNull == null ? clock.instant() : atOrNull;
        List<StageView> stages = workflowReader.activeStages(factoryId);
        List<UUID> stageIds = stages.stream().map(StageView::id).toList();
        List<WorkflowReader.RouteView> routes = workflowReader.activeRoutes(factoryId);

        List<ProductionOrder> placed = orders.findByFactoryIdAndPlacedAtLessThanEqualOrderByPlacedAtAscIdAsc(
                factoryId, at);
        Map<UUID, ProductionOrder> orderById = placed.stream()
                .collect(Collectors.toMap(ProductionOrder::getId, order -> order));
        List<ProductionBatch> placedBatches = placed.isEmpty() ? List.of()
                : batches.findByOrderIdInOrderByOrderIdAscBatchNumberAsc(orderById.keySet());

        List<OperationalEvent> known = events.findForStateAt(factoryId, at);
        Map<UUID, List<BatchHistory.Event>> historyByBatch = new HashMap<>();
        Set<UUID> dispatched = new HashSet<>();
        for (OperationalEvent event : known) {
            if (event.getBatchId() != null) {
                historyByBatch.computeIfAbsent(event.getBatchId(), id -> new ArrayList<>())
                        .add(EventAppendService.toHistory(event));
            } else if (event.getEventType() == EventType.ORDER_DISPATCHED) {
                dispatched.add(event.getOrderId());
            }
        }

        List<BatchState> batchStates = new ArrayList<>();
        Map<UUID, List<Placed>> projectionsByOrder = new HashMap<>();
        int[] queued = new int[stages.size()];
        int[] inProgress = new int[stages.size()];
        int[] completed = new int[stages.size()];
        Map<UUID, Integer> unitsInUse = new HashMap<>();

        for (ProductionBatch batch : placedBatches) {
            // Corrections were loaded whatever their date; their targets may lie after `at`, so the
            // effective list is cut to what had happened by then.
            List<BatchHistory.Event> effective = BatchHistory
                    .effective(historyByBatch.getOrDefault(batch.getId(), List.of())).stream()
                    .filter(event -> !event.occurredAt().isAfter(at))
                    .toList();
            List<BatchHistory.Stop> path = BatchPaths.of(stages, routes, batch.getRouteCode());
            Projection projection = BatchHistory.replay(stageIds, path,
                    new BigDecimal(batch.getStoneCount()).setScale(4), batch.getRoughCarats(), effective);
            if (projection.status() == null) {
                continue;
            }
            ProductionOrder order = orderById.get(batch.getOrderId());
            projectionsByOrder.computeIfAbsent(order.getId(), id -> new ArrayList<>()).add(new Placed(
                    projection.status(), projection.stageIndex(), stageIds.indexOf(path.get(0).stageId())));
            StageView stage = projection.stageId() == null ? null : stages.get(projection.stageIndex());
            // Stages of its path it has moved past (an optional one it skipped included).
            for (BatchHistory.Stop stop : path) {
                int i = stageIds.indexOf(stop.stageId());
                if (i < projection.stageIndex()) {
                    completed[i]++;
                }
            }
            if (projection.status() == BatchStatus.QUEUED) {
                queued[projection.stageIndex()]++;
            } else if (projection.status() == BatchStatus.IN_PROGRESS) {
                inProgress[projection.stageIndex()]++;
                for (RequirementView requirement : stage.requirements()) {
                    unitsInUse.merge(requirement.resourceId(), requirement.unitsPerSlot(), Integer::sum);
                }
            }
            batchStates.add(new BatchState(batch.getId(), order.getId(), order.getOrderNumber(),
                    batch.getBatchNumber(), order.getPriority(), projection.status(), projection.stageId(),
                    stage == null ? null : stage.code(), stage == null ? null : stage.sequence(),
                    projection.stones(), projection.carats(),
                    projection.status() == BatchStatus.QUEUED ? projection.queuedAt() : null,
                    projection.startedAt(), projection.completedAt(), batch.getRouteCode()));
        }
        batchStates.sort(Comparator
                .comparing((BatchState state) -> state.stageSequence() == null ? Integer.MAX_VALUE
                        : state.stageSequence())
                .thenComparing(BatchState::orderNumber)
                .thenComparingInt(BatchState::batchNumber));

        Map<OrderStatus, Long> orderCounts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            orderCounts.put(status, 0L);
        }
        for (ProductionOrder order : placed) {
            orderCounts.merge(statusOf(order, projectionsByOrder.getOrDefault(order.getId(), List.of()),
                    dispatched), 1L, Long::sum);
        }

        List<StageState> stageStates = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            StageView stage = stages.get(i);
            stageStates.add(new StageState(stage.id(), stage.code(), stage.name(), stage.sequence(), queued[i],
                    inProgress[i], completed[i]));
        }

        List<ResourceState> resourceStates = resourceReader.forFactory(factoryId).stream()
                .map(pool -> new ResourceState(pool.id(), pool.name(), pool.kind(), pool.capacity(),
                        CapacityTimeline.capacityAt(pool.capacity(),
                                resourceReader.windows(pool.id(), at, at.plusSeconds(1)), at),
                        unitsInUse.getOrDefault(pool.id(), 0)))
                .toList();

        return new FactoryStateResponse(factoryId, at, orderCounts, stageStates, batchStates, resourceStates,
                ProductionDtos.ACTUAL);
    }

    /** The dashboard's view of the present. */
    public FactoryOverviewResponse overview(UUID factoryId) {
        FactoryStateResponse state = stateAt(factoryId, null);
        Instant now = state.at();

        List<ProductionOrder> all = orders.findByFactoryId(factoryId);
        long pending = count(all, OrderStatus.PENDING);
        long inProgress = count(all, OrderStatus.IN_PROGRESS);
        OrderCounts orderCounts = new OrderCounts(all.size(), pending + inProgress, pending, inProgress,
                count(all, OrderStatus.COMPLETED), count(all, OrderStatus.DISPATCHED),
                all.stream().filter(order -> order.isDelayed(now)).count());

        return new FactoryOverviewResponse(factoryId, now, orderCounts, state.stages(), state.resources(),
                inUse(state.resources(), ResourceKind.MACHINE), inUse(state.resources(), ResourceKind.EMPLOYEE),
                dailyOutput(factoryId, now),
                eventQueryService.search(new EventFilter(factoryId, null, null, null, null, null, now, true, false),
                        false, 0, RECENT_EVENTS).items(),
                ProductionDtos.ACTUAL);
    }

    /** Finished stones and carats per factory-local day over the last two weeks, corrections applied. */
    private List<DailyOutput> dailyOutput(UUID factoryId, Instant now) {
        List<StageView> stages = workflowReader.activeStages(factoryId);
        if (stages.isEmpty()) {
            return List.of();
        }
        // A batch's output is its completion at the last stage of its own path.
        List<WorkflowReader.RouteView> routes = workflowReader.activeRoutes(factoryId);
        Map<String, UUID> lastStageByRoute = new HashMap<>();
        lastStageByRoute.put(null, stages.get(stages.size() - 1).id());
        for (WorkflowReader.RouteView route : routes) {
            lastStageByRoute.put(route.code(), route.steps().get(route.steps().size() - 1).stageId());
        }
        ZoneId zone = factoryService.calendar(factoryId).zone();
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate firstDay = today.minusDays(OVERVIEW_DAYS - 1L);
        Instant from = firstDay.atStartOfDay(zone).toInstant();

        List<OperationalEvent> completions = events
                .findByFactoryIdAndEventTypeAndOccurredAtGreaterThanEqualOrderBySeqAsc(factoryId,
                        EventType.STAGE_COMPLETED, from)
                .stream()
                .filter(event -> !event.getOccurredAt().isAfter(now))
                .toList();
        Map<UUID, String> routeByBatch = new HashMap<>();
        if (!routes.isEmpty() && !completions.isEmpty()) {
            batches.findAllById(completions.stream().map(OperationalEvent::getBatchId).distinct().toList())
                    .forEach(batch -> routeByBatch.put(batch.getId(), batch.getRouteCode()));
        }
        completions = completions.stream()
                .filter(event -> event.getStageId().equals(lastStageByRoute.get(routeByBatch.get(event.getBatchId()))))
                .toList();
        Map<LocalDate, List<ProductionDtos.EventResponse>> byDay = eventQueryService
                .toResponses(factoryId, completions).stream()
                .filter(event -> !event.voided())
                .collect(Collectors.groupingBy(event -> event.occurredAt().atZone(zone).toLocalDate(),
                        TreeMap::new, Collectors.toList()));

        List<DailyOutput> output = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            List<ProductionDtos.EventResponse> finished = byDay.getOrDefault(day, List.of());
            output.add(new DailyOutput(day, finished.size(),
                    finished.stream().map(ProductionDtos.EventResponse::stoneQuantity)
                            .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add),
                    finished.stream().map(ProductionDtos.EventResponse::caratQuantity)
                            .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add)));
        }
        return output;
    }

    /** A batch's projection and the stage its path starts at. */
    private record Placed(BatchStatus status, int stageIndex, int firstStageIndex) {
    }

    private static OrderStatus statusOf(ProductionOrder order, List<Placed> projections, Set<UUID> dispatched) {
        if (dispatched.contains(order.getId())) {
            return OrderStatus.DISPATCHED;
        }
        boolean allCompleted = !projections.isEmpty()
                && projections.stream().allMatch(p -> p.status() == BatchStatus.COMPLETED);
        if (allCompleted) {
            return OrderStatus.COMPLETED;
        }
        boolean anyStarted = projections.stream()
                .anyMatch(p -> p.status() != BatchStatus.QUEUED || p.stageIndex() != p.firstStageIndex());
        return anyStarted ? OrderStatus.IN_PROGRESS : OrderStatus.PENDING;
    }

    private static long count(List<ProductionOrder> all, OrderStatus status) {
        return all.stream().filter(order -> order.getStatus() == status).count();
    }

    private static UnitsInUse inUse(List<ResourceState> resources, ResourceKind kind) {
        int used = 0;
        int capacity = 0;
        for (ResourceState resource : resources) {
            if (resource.kind() == kind) {
                used += resource.unitsInUse();
                capacity += resource.capacity();
            }
        }
        return new UnitsInUse(used, capacity);
    }
}
