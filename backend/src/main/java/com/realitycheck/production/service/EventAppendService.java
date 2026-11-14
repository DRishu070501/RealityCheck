package com.realitycheck.production.service;

import com.realitycheck.common.domain.Ids;
import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.production.api.ProductionDtos.AppendEventRequest;
import com.realitycheck.production.domain.BatchStatus;
import com.realitycheck.production.domain.EventType;
import com.realitycheck.production.domain.OperationalEvent;
import com.realitycheck.production.domain.OrderStatus;
import com.realitycheck.production.domain.ProductionBatch;
import com.realitycheck.production.domain.ProductionOrder;
import com.realitycheck.production.persistence.OperationalEventRepository;
import com.realitycheck.production.persistence.ProductionBatchRepository;
import com.realitycheck.production.persistence.ProductionOrderRepository;
import com.realitycheck.production.service.BatchHistory.InvalidHistoryException;
import com.realitycheck.production.service.BatchHistory.Projection;
import com.realitycheck.workflow.service.WorkflowReader;
import com.realitycheck.workflow.service.WorkflowReader.StageView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way an operational event is written. Each method validates the event against the
 * batch's whole history, appends it with any events that follow from it, and brings the stored
 * projections (batch position, order status) up to date, all in one transaction.
 *
 * <p>Methods take the event id and the recording time so that the demonstration seed can supply
 * fixed values; the API passes a random id and the clock.
 */
@Service
public class EventAppendService {

    private static final String FOLLOW_ON_QUEUE = "queued-next";

    private final ProductionOrderRepository orders;
    private final ProductionBatchRepository batches;
    private final OperationalEventRepository events;
    private final WorkflowReader workflowReader;
    private final Clock clock;

    public EventAppendService(ProductionOrderRepository orders, ProductionBatchRepository batches,
            OperationalEventRepository events, WorkflowReader workflowReader, Clock clock) {
        this.orders = orders;
        this.batches = batches;
        this.events = events;
        this.workflowReader = workflowReader;
        this.clock = clock;
    }

    /** Entry point for {@code POST /api/events}: checks the fields each event type needs, then delegates. */
    @Transactional
    public OperationalEvent append(AppendEventRequest request) {
        Instant now = clock.instant();
        Instant occurredAt = request.occurredAt() == null ? now : request.occurredAt();
        if (occurredAt.isAfter(now)) {
            throw new ValidationException("occurredAt", "must not be in the future");
        }
        UUID id = Ids.random();
        return switch (request.eventType()) {
            case STAGE_STARTED -> stageStarted(required(request.batchId(), "batchId"), request.stageId(),
                    occurredAt, request.payload(), id, now);
            case STAGE_COMPLETED -> stageCompleted(required(request.batchId(), "batchId"), request.stageId(),
                    occurredAt, required(request.stoneQuantity(), "stoneQuantity"),
                    required(request.caratQuantity(), "caratQuantity"), request.durationSeconds(),
                    request.payload(), id, now);
            case ORDER_DISPATCHED -> orderDispatched(required(request.orderId(), "orderId"), occurredAt,
                    request.payload(), id, now);
            case CORRECTION -> correct(required(request.correctsEventId(), "correctsEventId"),
                    request.stoneQuantity(), request.caratQuantity(), request.durationSeconds(),
                    Boolean.TRUE.equals(request.voids()), request.payload(), id, now);
            default -> throw new ValidationException("eventType",
                    "is recorded by the system and cannot be posted");
        };
    }

    /** Records the placing of an order and queues each of its batches at the first stage. */
    @Transactional
    public void orderCreated(ProductionOrder order, List<ProductionBatch> orderBatches, UUID firstStageId,
            Instant recordedAt) {
        BigDecimal stones = BigDecimal.ZERO;
        BigDecimal carats = BigDecimal.ZERO;
        for (ProductionBatch batch : orderBatches) {
            stones = stones.add(BigDecimal.valueOf(batch.getStoneCount()));
            carats = carats.add(batch.getRoughCarats());
        }
        events.saveAndFlush(OperationalEvent
                .builder(Ids.child(order.getId(), "created"), recordedAt, order.getFactoryId(),
                        EventType.ORDER_CREATED, order.getPlacedAt())
                .order(order.getId())
                .quantities(stones.setScale(4, RoundingMode.UNNECESSARY), carats)
                .build());
        for (ProductionBatch batch : orderBatches) {
            events.saveAndFlush(OperationalEvent
                    .builder(Ids.child(batch.getId(), "queued-first"), recordedAt, order.getFactoryId(),
                            EventType.STAGE_QUEUED, order.getPlacedAt())
                    .order(order.getId())
                    .batch(batch.getId())
                    .stage(firstStageId)
                    .build());
        }
    }

    @Transactional
    public OperationalEvent stageStarted(UUID batchId, UUID stageIdOrNull, Instant occurredAt,
            Map<String, Object> payload, UUID id, Instant recordedAt) {
        BatchContext context = lockBatch(batchId, stageIdOrNull);
        OperationalEvent event = OperationalEvent
                .builder(id, recordedAt, context.order.getFactoryId(), EventType.STAGE_STARTED, occurredAt)
                .order(context.order.getId())
                .batch(batchId)
                .stage(context.batch.getCurrentStageId())
                .payload(payload)
                .build();
        apply(context, List.of(event), occurredAt, recordedAt);
        return event;
    }

    /**
     * @param stones          stones that left the stage; not more than entered it
     * @param durationSeconds working seconds spent, when known
     */
    @Transactional
    public OperationalEvent stageCompleted(UUID batchId, UUID stageIdOrNull, Instant occurredAt, BigDecimal stones,
            BigDecimal carats, Long durationSeconds, Map<String, Object> payload, UUID id, Instant recordedAt) {
        BatchContext context = lockBatch(batchId, stageIdOrNull);
        UUID stageId = context.batch.getCurrentStageId();
        List<OperationalEvent> added = new ArrayList<>();
        OperationalEvent event = OperationalEvent
                .builder(id, recordedAt, context.order.getFactoryId(), EventType.STAGE_COMPLETED, occurredAt)
                .order(context.order.getId())
                .batch(batchId)
                .stage(stageId)
                .quantities(stones.setScale(4, RoundingMode.UNNECESSARY), carats.setScale(4, RoundingMode.UNNECESSARY))
                .duration(durationSeconds)
                .payload(payload)
                .build();
        added.add(event);
        int index = context.stageIds.indexOf(stageId);
        if (index >= 0 && index < context.stageIds.size() - 1) {
            added.add(OperationalEvent
                    .builder(Ids.child(id, FOLLOW_ON_QUEUE), recordedAt, context.order.getFactoryId(),
                            EventType.STAGE_QUEUED, occurredAt)
                    .order(context.order.getId())
                    .batch(batchId)
                    .stage(context.stageIds.get(index + 1))
                    .build());
        }
        apply(context, added, occurredAt, recordedAt);
        return event;
    }

    @Transactional
    public OperationalEvent orderDispatched(UUID orderId, Instant occurredAt, Map<String, Object> payload, UUID id,
            Instant recordedAt) {
        ProductionOrder order = orders.findForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order", orderId));
        if (order.getStatus() == OrderStatus.DISPATCHED) {
            throw ApiException.unprocessable("Order " + order.getOrderNumber() + " has already been dispatched");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw ApiException.unprocessable("Order " + order.getOrderNumber()
                    + " cannot be dispatched until every batch has finished the last stage");
        }
        if (occurredAt.isBefore(order.getCompletedAt())) {
            throw ApiException.unprocessable("An order cannot be dispatched before it was completed");
        }
        OperationalEvent event = events.saveAndFlush(OperationalEvent
                .builder(id, recordedAt, order.getFactoryId(), EventType.ORDER_DISPATCHED, occurredAt)
                .order(orderId)
                .payload(payload)
                .build());
        order.project(OrderStatus.DISPATCHED, order.getCompletedAt(), recordedAt);
        return event;
    }

    /**
     * Corrects a recorded stage step without touching it. A correction either replaces the output
     * quantities or duration of a {@code STAGE_COMPLETED}, or voids a {@code STAGE_STARTED} or
     * {@code STAGE_COMPLETED}. The batch's whole history must still be possible afterwards.
     */
    @Transactional
    public OperationalEvent correct(UUID targetId, BigDecimal stones, BigDecimal carats, Long durationSeconds,
            boolean voids, Map<String, Object> payload, UUID id, Instant recordedAt) {
        OperationalEvent target = events.findById(targetId)
                .orElseThrow(() -> new ValidationException("correctsEventId", "does not exist"));
        boolean replaces = stones != null || carats != null || durationSeconds != null;
        if (voids == replaces) {
            throw new ValidationException("voids",
                    "a correction must either void the event or replace a quantity or duration, not both");
        }
        if (target.getBatchId() == null || !(target.getEventType() == EventType.STAGE_COMPLETED
                || (voids && target.getEventType() == EventType.STAGE_STARTED))) {
            throw ApiException.unprocessable(voids
                    ? "Only a stage start or a stage completion can be voided"
                    : "Only the output of a completed stage can be corrected");
        }
        if (events.existsByCorrectsEventIdAndVoidsTrue(targetId)) {
            throw ApiException.unprocessable("That event has been voided and cannot be corrected");
        }
        BatchContext context = lockBatch(target.getBatchId(), null, true);
        if (voids && context.batch.getStatus() == BatchStatus.COMPLETED) {
            throw ApiException.unprocessable("A batch that has finished the last stage cannot be reopened");
        }

        List<OperationalEvent> added = new ArrayList<>();
        OperationalEvent correction = OperationalEvent
                .builder(id, recordedAt, target.getFactoryId(), EventType.CORRECTION, recordedAt)
                .order(target.getOrderId())
                .batch(target.getBatchId())
                .stage(target.getStageId())
                .quantities(stones == null ? null : stones.setScale(4, RoundingMode.UNNECESSARY),
                        carats == null ? null : carats.setScale(4, RoundingMode.UNNECESSARY))
                .duration(durationSeconds)
                .payload(payload)
                .corrects(targetId, voids)
                .build();
        added.add(correction);
        UUID followOnId = Ids.child(targetId, FOLLOW_ON_QUEUE);
        if (voids && target.getEventType() == EventType.STAGE_COMPLETED && events.existsById(followOnId)) {
            // The batch never reached the next queue either.
            added.add(OperationalEvent
                    .builder(Ids.child(id, "void-follow-on"), recordedAt, target.getFactoryId(),
                            EventType.CORRECTION, recordedAt)
                    .order(target.getOrderId())
                    .batch(target.getBatchId())
                    .corrects(followOnId, true)
                    .build());
        }
        apply(context, added, null, recordedAt);
        return correction;
    }

    /** Records that units of a pool became unavailable. Not tied to a batch, so nothing is replayed. */
    @Transactional
    public void resourceUnavailable(UUID factoryId, UUID resourceId, Instant startsAt, Instant endsAt, int units,
            String reason, UUID id, Instant recordedAt) {
        events.saveAndFlush(OperationalEvent
                .builder(id, recordedAt, factoryId, EventType.RESOURCE_UNAVAILABLE, startsAt)
                .resource(resourceId)
                .duration(endsAt.getEpochSecond() - startsAt.getEpochSecond())
                .payload(Map.of("unitsUnavailable", units, "endsAt", endsAt.toString(), "reason", reason))
                .build());
    }

    private record BatchContext(ProductionBatch batch, ProductionOrder order, List<UUID> stageIds) {
    }

    private BatchContext lockBatch(UUID batchId, UUID stageIdOrNull) {
        return lockBatch(batchId, stageIdOrNull, false);
    }

    private BatchContext lockBatch(UUID batchId, UUID stageIdOrNull, boolean allowCompleted) {
        ProductionBatch batch = batches.findForUpdate(batchId)
                .orElseThrow(() -> new ValidationException("batchId", "does not exist"));
        ProductionOrder order = orders.findById(batch.getOrderId()).orElseThrow();
        List<UUID> stageIds = workflowReader.activeStages(order.getFactoryId()).stream().map(StageView::id).toList();
        if (!allowCompleted && batch.getStatus() == BatchStatus.COMPLETED) {
            throw ApiException.unprocessable("The batch has already finished the last stage");
        }
        if (stageIdOrNull != null && !stageIdOrNull.equals(batch.getCurrentStageId())) {
            throw ApiException.unprocessable("The batch is not at that stage");
        }
        return new BatchContext(batch, order, stageIds);
    }

    /**
     * Validates the batch's history with the new events added, stores them, and updates the batch
     * and order projections.
     *
     * @param stepOccurredAt when a new step happened, or null for corrections
     */
    private void apply(BatchContext context, List<OperationalEvent> added, Instant stepOccurredAt,
            Instant recordedAt) {
        ProductionBatch batch = context.batch;
        BigDecimal initialStones = new BigDecimal(batch.getStoneCount()).setScale(4);
        List<BatchHistory.Event> history = new ArrayList<>(
                events.findByBatchIdOrderBySeqAsc(batch.getId()).stream().map(EventAppendService::toHistory).toList());

        Projection projection;
        try {
            if (stepOccurredAt != null) {
                Projection before = BatchHistory.replay(context.stageIds, initialStones, batch.getRoughCarats(),
                        BatchHistory.effective(history));
                if (before.lastEventAt() != null && stepOccurredAt.isBefore(before.lastEventAt())) {
                    throw new InvalidHistoryException(
                            "An event cannot occur before the batch's previous event at " + before.lastEventAt());
                }
            }
            long pendingSeq = Long.MAX_VALUE - added.size();
            for (OperationalEvent event : added) {
                history.add(toHistory(event, pendingSeq++));
            }
            projection = BatchHistory.replay(context.stageIds, initialStones, batch.getRoughCarats(),
                    BatchHistory.effective(history));
        } catch (InvalidHistoryException ex) {
            throw ApiException.unprocessable(ex.getMessage());
        }

        for (OperationalEvent event : added) {
            events.saveAndFlush(event);
        }
        batch.project(projection.status(), projection.stageId(), projection.stones(), projection.carats(),
                recordedAt);
        batches.saveAndFlush(batch);
        updateOrder(context, recordedAt);
    }

    private void updateOrder(BatchContext context, Instant recordedAt) {
        ProductionOrder order = orders.findForUpdate(context.order.getId()).orElseThrow();
        if (order.getStatus() == OrderStatus.DISPATCHED) {
            return;
        }
        List<ProductionBatch> siblings = batches.findByOrderIdOrderByBatchNumberAsc(order.getId());
        UUID firstStage = context.stageIds.get(0);
        UUID lastStage = context.stageIds.get(context.stageIds.size() - 1);
        boolean allCompleted = siblings.stream().allMatch(b -> b.getStatus() == BatchStatus.COMPLETED);
        boolean anyStarted = siblings.stream().anyMatch(b -> b.getStatus() != BatchStatus.QUEUED
                || !firstStage.equals(b.getCurrentStageId()));

        if (allCompleted) {
            Instant completedAt = events.findLatestOccurrence(order.getId(), EventType.STAGE_COMPLETED, lastStage);
            if (order.getStatus() != OrderStatus.COMPLETED) {
                events.saveAndFlush(OperationalEvent
                        .builder(Ids.child(order.getId(), "completed"), recordedAt, order.getFactoryId(),
                                EventType.ORDER_COMPLETED, completedAt)
                        .order(order.getId())
                        .build());
            }
            order.project(OrderStatus.COMPLETED, completedAt, recordedAt);
        } else {
            order.project(anyStarted ? OrderStatus.IN_PROGRESS : OrderStatus.PENDING, null, recordedAt);
        }
    }

    static BatchHistory.Event toHistory(OperationalEvent event) {
        return toHistory(event, event.getSeq());
    }

    private static BatchHistory.Event toHistory(OperationalEvent event, long seq) {
        return new BatchHistory.Event(event.getId(), seq, event.getEventType(), event.getStageId(),
                event.getOccurredAt(), event.getStoneQuantity(), event.getCaratQuantity(),
                event.getDurationSeconds(), event.getCorrectsEventId(), event.isVoids());
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new ValidationException(field, "is required for this event type");
        }
        return value;
    }
}
