package com.realitycheck.production.service;

import com.realitycheck.common.api.PageResponse;
import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.service.FactoryCountProvider;
import com.realitycheck.factory.service.FactoryResolver;
import com.realitycheck.production.api.ProductionDtos;
import com.realitycheck.production.api.ProductionDtos.BatchResponse;
import com.realitycheck.production.api.ProductionDtos.CreateBatchRequest;
import com.realitycheck.production.api.ProductionDtos.CreateOrderRequest;
import com.realitycheck.production.api.ProductionDtos.OrderDetailResponse;
import com.realitycheck.production.api.ProductionDtos.OrderResponse;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.OrderStatus;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.domain.ProductionOrder;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.production.persistence.ProductionOrderRepository;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService implements FactoryCountProvider {

    private final ProductionOrderRepository orders;
    private final ProductionBatchRepository batches;
    private final OperationalEventRepository events;
    private final EventAppendService eventAppendService;
    private final EventQueryService eventQueryService;
    private final FactoryResolver factoryResolver;
    private final WorkflowReader workflowReader;
    private final Clock clock;

    public OrderService(ProductionOrderRepository orders, ProductionBatchRepository batches,
            OperationalEventRepository events, EventAppendService eventAppendService,
            EventQueryService eventQueryService, FactoryResolver factoryResolver, WorkflowReader workflowReader,
            Clock clock) {
        this.orders = orders;
        this.batches = batches;
        this.events = events;
        this.eventAppendService = eventAppendService;
        this.eventQueryService = eventQueryService;
        this.factoryResolver = factoryResolver;
        this.workflowReader = workflowReader;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Instant now = clock.instant();
        if (request.placedAt() != null && request.placedAt().isAfter(now)) {
            throw new ValidationException("placedAt", "must not be in the future");
        }
        return create(request, Ids.random(), now);
    }

    /**
     * Creates the order, its batches, and the events that record it, or nothing at all.
     * The id and recording time are parameters so that the demonstration seed can fix them.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request, UUID id, Instant recordedAt) {
        UUID factoryId = factoryResolver.resolve(request.factoryId());
        List<StageView> stages = workflowReader.activeStages(factoryId);
        if (stages.isEmpty()) {
            throw ApiException.unprocessable("The factory has no active workflow to run the order through");
        }
        Instant placedAt = request.placedAt() == null ? recordedAt : request.placedAt();
        if (request.dueAt() != null && !request.dueAt().isAfter(placedAt)) {
            throw new ValidationException("dueAt", "must be after the order was placed");
        }
        String orderNumber = request.orderNumber().strip();
        if (orders.existsByFactoryIdAndOrderNumberIgnoreCase(factoryId, orderNumber)) {
            throw ApiException.conflict("Order number " + orderNumber + " is already used in this factory");
        }

        ProductionOrder order = orders.saveAndFlush(new ProductionOrder(id, recordedAt, factoryId, orderNumber,
                blankToNull(request.customerRef()), request.priority(), request.dueAt(), placedAt));
        List<WorkflowReader.RouteView> routes = workflowReader.activeRoutes(factoryId);
        List<ProductionBatch> created = new ArrayList<>();
        int number = 1;
        for (CreateBatchRequest batch : request.batches()) {
            UUID firstStage;
            try {
                firstStage = BatchPaths.firstStage(stages, routes, batch.routeCode());
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("batches[" + (number - 1) + "].routeCode", ex.getMessage());
            }
            created.add(batches.saveAndFlush(new ProductionBatch(Ids.child(id, "batch:" + number), recordedAt, id,
                    number, batch.stoneCount(), batch.roughCarats().setScale(4, RoundingMode.UNNECESSARY),
                    firstStage, batch.routeCode())));
            number++;
        }
        eventAppendService.orderCreated(order, created, recordedAt);
        return toResponse(order, created, recordedAt);
    }

    /**
     * @param delayedOrNull when given, only orders that are (true) or are not (false) late
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(UUID factoryIdOrNull, OrderStatus statusOrNull, Boolean delayedOrNull,
            Pageable pageable) {
        UUID factoryId = factoryResolver.resolve(factoryIdOrNull);
        Instant now = clock.instant();
        Page<ProductionOrder> page = orders.findAll(specification(factoryId, statusOrNull, delayedOrNull, now),
                pageable);
        Map<UUID, List<ProductionBatch>> batchesByOrder = batches
                .findByOrderIdInOrderByOrderIdAscBatchNumberAsc(page.getContent().stream()
                        .map(ProductionOrder::getId).toList())
                .stream().collect(Collectors.groupingBy(ProductionBatch::getOrderId));
        return PageResponse.from(page,
                order -> toResponse(order, batchesByOrder.getOrDefault(order.getId(), List.of()), now));
    }

    /** An order with its batches and its full event history in recording order. */
    @Transactional(readOnly = true)
    public OrderDetailResponse get(UUID id) {
        ProductionOrder order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
        List<ProductionBatch> orderBatches = batches.findByOrderIdOrderByBatchNumberAsc(id);
        Map<UUID, StageView> stageById = workflowReader.activeStages(order.getFactoryId()).stream()
                .collect(Collectors.toMap(StageView::id, stage -> stage));
        List<BatchResponse> batchResponses = orderBatches.stream().map(batch -> {
            StageView stage = stageById.get(batch.getCurrentStageId());
            return new BatchResponse(batch.getId(), batch.getBatchNumber(), batch.getStoneCount(),
                    batch.getRoughCarats(), batch.getCurrentStones(), batch.getCurrentCarats(),
                    batch.getCurrentStageId(), stage == null ? null : stage.code(),
                    stage == null ? null : stage.name(), stage == null ? null : stage.sequence(),
                    batch.getStatus());
        }).toList();
        return new OrderDetailResponse(toResponse(order, orderBatches, clock.instant()), batchResponses,
                eventQueryService.toResponses(order.getFactoryId(), events.findByOrderIdOrderBySeqAsc(id)));
    }

    @Override
    public String key() {
        return "orders";
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID factoryId) {
        return orders.countByFactoryId(factoryId);
    }

    private static Specification<ProductionOrder> specification(UUID factoryId, OrderStatus status, Boolean delayed,
            Instant now) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("factoryId"), factoryId));
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (delayed != null) {
                Predicate late = cb.and(cb.isNotNull(root.get("dueAt")), cb.or(
                        cb.and(cb.isNotNull(root.get("completedAt")),
                                cb.greaterThan(root.<Instant>get("completedAt"), root.<Instant>get("dueAt"))),
                        cb.and(cb.isNull(root.get("completedAt")),
                                cb.lessThan(root.<Instant>get("dueAt"), now))));
                where.add(delayed ? late : cb.not(late));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static OrderResponse toResponse(ProductionOrder order, List<ProductionBatch> orderBatches, Instant now) {
        int stones = orderBatches.stream().mapToInt(ProductionBatch::getStoneCount).sum();
        BigDecimal carats = orderBatches.stream().map(ProductionBatch::getRoughCarats)
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
        int completed = (int) orderBatches.stream().filter(b -> b.getStatus() == BatchStatus.COMPLETED).count();
        return new OrderResponse(order.getId(), order.getFactoryId(), order.getOrderNumber(),
                order.getCustomerRef(), order.getPriority(), order.getStatus(), order.getPlacedAt(),
                order.getDueAt(), order.getCompletedAt(), order.isDelayed(now), orderBatches.size(), completed,
                stones, carats, ProductionDtos.ACTUAL);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
